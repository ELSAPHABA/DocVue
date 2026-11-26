import hashlib
import io
import json
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

import worker


class WorkerTests(unittest.TestCase):
    def setUp(self):
        self.content = b"test data"
        self.sha = hashlib.sha256(self.content).hexdigest()
        self.data = {"jobId": "job-1", "fence": 3, "originalKey": "originals/a",
                     "expectedBytes": len(self.content), "expectedSha256": self.sha,
                     "outputPrefix": "attempts/job-1/3/"}
        self.claim = worker.Claim.parse("job-1", self.data)
        self.core, self.s3, self.sqs = Mock(), Mock(), Mock()
        self.queue = worker.Worker(self.core, self.s3, self.sqs, "bucket", "queue")
        self.message = {"Body": json.dumps({"payload": {"jobId": "job-1"}}),
                        "ReceiptHandle": "receipt"}

    def test_message_and_claim_validation(self):
        self.assertEqual(worker.message_job_id('{"jobId":"job-1"}'), "job-1")
        self.assertEqual(worker.message_job_id(self.message["Body"]), "job-1")
        for body in ("{}", '{"jobId":"../unsafe"}', "[]"):
            with self.assertRaises(ValueError):
                worker.message_job_id(body)
        for field, value in (("expectedBytes", worker.MAX_BYTES + 1),
                             ("expectedSha256", "invalid"), ("outputPrefix", "bad")):
            with self.assertRaises(worker.JobError):
                worker.Claim.parse("job-1", dict(self.data, **{field: value}))

    def test_terminal_and_conflict_acknowledgement(self):
        self.core.post.return_value = (409, None)
        self.queue.handle(self.message)
        self.sqs.delete_message.assert_not_called()
        self.core.post.return_value = (204, None)
        self.queue.handle(self.message)
        self.sqs.delete_message.assert_called_once_with(QueueUrl="queue", ReceiptHandle="receipt")

    def test_streaming_full_checksum(self):
        lease = Mock()
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "source"
            body = io.BytesIO(self.content)
            self.s3.get_object.return_value = {"ContentLength": len(self.content), "Body": body}
            self.assertEqual(worker.download(self.s3, "bucket", self.claim, path, lease), self.sha)
            self.assertEqual(path.read_bytes(), self.content)
            self.assertTrue(body.closed)
            self.s3.get_object.return_value = {"ContentLength": len(self.content),
                                                "Body": io.BytesIO(b"x" * len(self.content))}
            with self.assertRaisesRegex(worker.JobError, "CHECKSUM_MISMATCH"):
                worker.download(self.s3, "bucket", self.claim, path, lease)

    def test_completion_acknowledged_only_after_core_commit(self):
        self.core.post.side_effect = [(200, self.data), (204, None)]
        with patch.object(worker, "download", return_value=self.sha), \
                patch.object(worker, "render", return_value=(5000, [])), \
                patch.object(worker, "upload"):
            self.queue.handle(self.message)
        self.core.post.assert_called_with("job-1", "complete",
            {"fence": 3, "durationMs": 5000, "sha256": self.sha})
        self.sqs.delete_message.assert_called_once()

    def test_completion_conflict_not_deleted(self):
        self.core.post.side_effect = [(200, self.data), (409, None)]
        with patch.object(worker, "download", return_value=self.sha), \
                patch.object(worker, "render", return_value=(5000, [])), \
                patch.object(worker, "upload"):
            self.queue.handle(self.message)
        self.sqs.delete_message.assert_not_called()

    def test_lost_lease_never_completes_or_deletes(self):
        self.core.post.return_value = (200, self.data)
        with patch.object(worker, "download", side_effect=worker.LeaseLost):
            self.queue.handle(self.message)
        self.core.post.assert_called_once_with("job-1", "claim")
        self.sqs.delete_message.assert_not_called()

    def test_invalid_input_terminal_failure(self):
        self.core.post.side_effect = [(200, self.data), (204, None)]
        with patch.object(worker, "download", side_effect=worker.JobError("CHECKSUM_MISMATCH")):
            self.queue.handle(self.message)
        self.core.post.assert_called_with("job-1", "fail",
            {"fence": 3, "code": "CHECKSUM_MISMATCH", "retryable": False})
        self.sqs.delete_message.assert_called_once()

    def test_heartbeat_and_visibility(self):
        self.core.post.side_effect = [(204, None), (409, None)]
        lease = worker.Lease(self.core, self.sqs, "queue", "receipt", self.claim, interval=0.01)
        lease.start()
        self.assertTrue(lease.lost.wait(2))
        self.assertRaises(worker.LeaseLost, lease.check)
        lease.stop()
        self.sqs.change_message_visibility.assert_called_once_with(
            QueueUrl="queue", ReceiptHandle="receipt", VisibilityTimeout=120)

    def test_heights_no_upscale(self):
        for height, expected in ((359, [358]), (360, [360]), (800, [360, 720]),
                                 (1080, [360, 720, 1080])):
            self.assertEqual(worker.heights(worker.Video(1, 1920, height, 0, None)), expected)


@unittest.skipUnless(sys.platform.startswith("linux"), "Linux sandbox required")
class SandboxTests(unittest.TestCase):
    def test_decoder_network_syscall_blocked(self):
        script = "import socket; socket.socket()"
        result = subprocess.run([sys.executable, str(Path(worker.__file__).with_name("sandbox.py")),
                                 sys.executable, "-c", script],
                                capture_output=True, timeout=10)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b"PermissionError", result.stderr)


@unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "FFmpeg unavailable")
class FFmpegTests(unittest.TestCase):
    def test_real_hls_render_and_unsafe_playlist_rejected(self):
        core, sqs = Mock(), Mock()
        claim = worker.Claim("job", 1, "original", 1, "0"*64, "attempt/")
        lease = worker.Lease(core, sqs, "queue", "receipt", claim, timeout=180)
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source, output = root / "source", root / "hls"
            subprocess.run(["ffmpeg", "-v", "error", "-f", "lavfi", "-i",
                            "testsrc2=size=1280x720:rate=30", "-f", "lavfi", "-i",
                            "sine=frequency=400:sample_rate=44100", "-t", "5",
                            "-c:v", "libx264", "-c:a", "aac", "-threads", "2", "-f", "mp4",
                            str(source)], check=True, timeout=60)
            duration, files = worker.render(source, output, lease)
            self.assertAlmostEqual(duration, 5000, delta=100)
            self.assertEqual(files[-1], output / "master.m3u8")
            self.assertIn("v360/index.m3u8", files[-1].read_text())
            self.assertIn("v720/index.m3u8", files[-1].read_text())
            self.assertEqual(len(list((output / "v360").glob("*.ts"))), 2)
            self.assertEqual(len(list((output / "v720").glob("*.ts"))), 2)
            playlist = output / "v360/index.m3u8"
            playlist.write_text(playlist.read_text().replace("seg00000.ts", "../escape.ts"))
            with self.assertRaisesRegex(worker.JobError, "OUTPUT_INVALID"):
                worker.validate_variant(output / "v360", worker.probe(source, lease),
                                        640, 360, lease)

    def test_small_webm_fallback_and_matroska_rejection(self):
        claim = worker.Claim("job", 1, "original", 1, "0"*64, "attempt/")
        lease = worker.Lease(Mock(), Mock(), "queue", "receipt", claim, timeout=180)
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source, output = root / "source", root / "hls"
            subprocess.run(["ffmpeg", "-v", "error", "-f", "lavfi", "-i",
                            "testsrc2=size=320x240:rate=24", "-t", "1", "-c:v",
                            "libvpx-vp9", "-threads", "2", "-f", "webm", str(source)],
                           check=True, timeout=60)
            duration, files = worker.render(source, output, lease)
            self.assertAlmostEqual(duration, 1000, delta=100)
            self.assertIn("v240/index.m3u8", files[-1].read_text())
            mkv = root / "unsupported"
            subprocess.run(["ffmpeg", "-v", "error", "-i", str(source), "-c",
                            "copy", "-f", "matroska", str(mkv)], check=True, timeout=30)
            with self.assertRaisesRegex(worker.JobError, "INVALID_MEDIA"):
                worker.probe(mkv, lease)


if __name__ == "__main__":
    unittest.main()
