"""Fenced SQS-to-HLS worker; Core owns all durable job state."""
from __future__ import annotations

import hashlib
import json
import logging
import math
import os
import random
import re
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path

LOG = logging.getLogger("media-worker")
MAX_BYTES = 5 * 1024**3
MAX_DURATION = 4 * 3600
JOB_TIMEOUT = 2 * 3600
VISIBILITY = 120
HEARTBEAT = 30
CHUNK = 1024**2
SEGMENT = re.compile(r"seg\d{5}\.ts")
JOB_ID = re.compile(r"[A-Za-z0-9_-]{1,128}")
SHA = re.compile(r"[a-fA-F0-9]{64}")
# Upper bound for temporary encoded output, in addition to the original.
MAX_OUTPUT = 24 * 1024**3


class JobError(Exception):
    def __init__(self, code: str, retryable: bool = False):
        super().__init__(code)
        self.code, self.retryable = code, retryable


class LeaseLost(Exception):
    pass


@dataclass(frozen=True)
class Claim:
    job_id: str
    fence: int
    original: str
    size: int
    sha256: str
    prefix: str

    @classmethod
    def parse(cls, job_id, data):
        try:
            claim = cls(data["jobId"], data["fence"], data["originalKey"],
                        data["expectedBytes"], data["expectedSha256"], data["outputPrefix"])
            if (claim.job_id != job_id or type(claim.fence) is not int or claim.fence < 1
                    or type(claim.size) is not int or not 0 < claim.size <= MAX_BYTES
                    or not isinstance(claim.sha256, str) or not SHA.fullmatch(claim.sha256)
                    or not isinstance(claim.original, str) or not claim.original
                    or not isinstance(claim.prefix, str) or not claim.prefix.endswith("/")
                    or claim.original.startswith(claim.prefix)):
                raise ValueError()
            return claim
        except (KeyError, ValueError, TypeError):
            raise JobError("INVALID_CLAIM", True) from None


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Core:
    def __init__(self, base, token):
        self.base, self.token = base.rstrip("/"), token
        # Do not leak the internal token via redirects or an environment proxy.
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def post(self, job_id, action, body=None):
        req = urllib.request.Request(
            f"{self.base}/{job_id}/{action}", method="POST",
            headers={"X-Worker-Token": self.token, "Content-Type": "application/json"},
            data=json.dumps(body if body is not None else {}).encode())
        try:
            with self.opener.open(req, timeout=10) as response:
                if response.status == 200:
                    raw = response.read(65537)
                    if len(raw) > 65536:
                        raise JobError("CORE_RESPONSE", True)
                    return response.status, json.loads(raw)
                return response.status, None
        except urllib.error.HTTPError as err:
            status = err.code
            err.close()
            return status, None
        except (OSError, ValueError):
            raise JobError("CORE_UNAVAILABLE", True) from None


class Lease:
    def __init__(self, core, sqs, queue_url, receipt, claim,
                 interval=HEARTBEAT, timeout=JOB_TIMEOUT):
        self.core, self.sqs, self.queue_url, self.receipt = core, sqs, queue_url, receipt
        self.claim, self.interval = claim, interval
        self.deadline = time.monotonic() + timeout
        self.stopped = threading.Event()
        self.lost = threading.Event()
        self.thread = threading.Thread(target=self._run, daemon=True)

    def start(self):
        self.thread.start()

    def _run(self):
        while not self.stopped.wait(self.interval):
            try:
                status, _ = self.core.post(self.claim.job_id, "heartbeat",
                                           {"fence": self.claim.fence})
                if status != 204:
                    raise LeaseLost()
                self.sqs.change_message_visibility(QueueUrl=self.queue_url,
                    ReceiptHandle=self.receipt, VisibilityTimeout=VISIBILITY)
            except Exception:
                self.lost.set()
                return

    def check(self):
        if self.lost.is_set():
            raise LeaseLost()
        if time.monotonic() >= self.deadline:
            raise JobError("JOB_TIMEOUT", True)

    def stop(self):
        self.stopped.set()
        self.thread.join()


def message_job_id(body):
    data = json.loads(body)
    if not isinstance(data, dict):
        raise ValueError()
    payload = data.get("payload")
    job_id = data.get("jobId") or (payload.get("jobId") if isinstance(payload, dict) else None)
    if not isinstance(job_id, str) or not JOB_ID.fullmatch(job_id):
        raise ValueError()
    return job_id


def download(s3, bucket, claim, destination, lease):
    response = s3.get_object(Bucket=bucket, Key=claim.original)
    body = response["Body"]
    digest, count = hashlib.sha256(), 0
    try:
        if response.get("ContentLength") != claim.size:
            raise JobError("SIZE_MISMATCH")
        with destination.open("wb") as output:
            while True:
                lease.check()
                chunk = body.read(CHUNK)
                if not chunk:
                    break
                count += len(chunk)
                if count > claim.size or count > MAX_BYTES:
                    raise JobError("SIZE_MISMATCH")
                digest.update(chunk)
                output.write(chunk)
    finally:
        body.close()
    if count != claim.size:
        raise JobError("SIZE_MISMATCH")
    sha256 = digest.hexdigest()
    if sha256 != claim.sha256.lower():
        raise JobError("CHECKSUM_MISMATCH")
    return sha256


def run_process(args, lease, *, timeout, capture=False, output_dir=None):
    # Restrict child environment: no AWS credentials or worker token.
    env = {key: os.environ[key] for key in ("PATH", "SystemRoot", "WINDIR", "TEMP", "TMP")
           if key in os.environ}
    env["LC_ALL"] = "C"
    if sys.platform.startswith("linux"):
        args = [sys.executable, str(Path(__file__).with_name("sandbox.py")), *args]
    deadline = min(time.monotonic() + timeout, lease.deadline)
    # The subprocess writes probe JSON to a file, so a large stdout cannot deadlock it.
    with tempfile.TemporaryFile() as stdout:
        with subprocess.Popen(args, stdin=subprocess.DEVNULL,
                              stdout=stdout if capture else subprocess.DEVNULL,
                              stderr=subprocess.DEVNULL, env=env) as proc:
            try:
                while proc.poll() is None:
                    lease.check()
                    if time.monotonic() >= deadline:
                        raise JobError("PROCESS_TIMEOUT", True)
                    if capture and stdout.tell() > 1024**2:
                        raise JobError("INVALID_MEDIA")
                    if output_dir is not None and directory_size(output_dir) > MAX_OUTPUT:
                        raise JobError("OUTPUT_LIMIT")
                    time.sleep(0.25 if capture else 1)
                lease.check()
                if proc.returncode:
                    raise JobError("INVALID_MEDIA" if capture else "ENCODE_FAILED")
                if output_dir is not None and directory_size(output_dir) > MAX_OUTPUT:
                    raise JobError("OUTPUT_LIMIT")
                if capture:
                    stdout.seek(0)
                    raw = stdout.read(1024**2 + 1)
                    if len(raw) > 1024**2:
                        raise JobError("INVALID_MEDIA")
                    try:
                        return json.loads(raw)
                    except ValueError:
                        raise JobError("INVALID_MEDIA") from None
            finally:
                if proc.poll() is None:
                    proc.kill()
                    proc.wait()


def directory_size(directory):
    total = 0
    for path in directory.rglob("*"):
        try:
            if path.is_file():
                total += path.stat().st_size
        except FileNotFoundError:
            # FFmpeg may replace an intermediate playlist during the scan.
            continue
    return total


@dataclass(frozen=True)
class Video:
    duration: float
    width: int
    height: int
    video_index: int
    audio_index: int | None


def probe(path, lease, *, segment=False):
    # Limit demuxers to accepted local containers. Disable all network protocols.
    result = run_process([
        "ffprobe", "-v", "error", "-protocol_whitelist", "file,pipe",
        "-format_whitelist", "mpegts" if segment else "mov,matroska,webm",
        "-probesize", "10000000", "-analyzeduration", "10000000",
        "-show_entries", "format=format_name,duration:format_tags=major_brand:"
        "stream=index,codec_type,codec_name,width,height:stream_disposition=attached_pic:"
        "stream_side_data=rotation",
        "-of", "json", str(path),
    ], lease, timeout=60, capture=True)
    try:
        streams = result["streams"]
        video = next(s for s in streams if s["codec_type"] == "video"
                     and not s.get("disposition", {}).get("attached_pic", 0))
        audio = next((s for s in streams if s["codec_type"] == "audio"), None)
        width, height = int(video["width"]), int(video["height"])
        # TS may not advertise a duration for a very short segment. The
        # independently validated VOD playlist supplies segment durations.
        duration = float(result["format"].get("duration", 0) if segment
                         else result["format"]["duration"])
        names = set(result["format"]["format_name"].split(","))
        if (not math.isfinite(duration) or duration > MAX_DURATION
                or (not segment and duration <= 0)):
            raise ValueError()
        if not (2 <= width <= 8192 and 2 <= height <= 8192):
            raise ValueError()
        for side in video.get("side_data_list", []):
            if int(side.get("rotation", 0)) % 180 == 90:
                width, height = height, width
                break
        if segment:
            if "mpegts" not in names or video["codec_name"] != "h264" or (
                    audio is not None and audio["codec_name"] != "aac"):
                raise ValueError()
        elif "mov" in names:
            brand = result["format"].get("tags", {}).get("major_brand", "").strip().lower()
            if brand.startswith(("3g", "mj2", "mjp2", "m4a")):
                raise ValueError()
        elif "webm" in names:
            if not is_webm(path):
                raise ValueError()
        else:
            raise ValueError()
        return Video(duration, width, height, int(video["index"]),
                     int(audio["index"]) if audio is not None else None)
    except (KeyError, StopIteration, TypeError, ValueError, OverflowError):
        raise JobError("OUTPUT_INVALID" if segment else "INVALID_MEDIA") from None


def is_webm(path):
    # Matroska and WebM share the FFmpeg demuxer; validate EBML DocType.
    with path.open("rb") as file:
        data = file.read(4096)

    def vint(pos, keep_marker=False):
        first = data[pos]
        size = 1
        mask = 0x80
        while size <= 8 and not first & mask:
            size += 1
            mask >>= 1
        if size > 8 or pos + size > len(data):
            raise ValueError()
        value = int.from_bytes(data[pos:pos + size], "big")
        return (value if keep_marker else value ^ (mask << (8 * (size - 1)))), pos + size

    try:
        element, pos = vint(0, True)
        length, pos = vint(pos)
        end = pos + length
        if element != 0x1A45DFA3 or end > len(data):
            return False
        while pos < end:
            element, pos = vint(pos, True)
            length, pos = vint(pos)
            if pos + length > end:
                return False
            if element == 0x4282:
                return data[pos:pos + length] == b"webm"
            pos += length
    except (IndexError, ValueError):
        pass
    return False


def heights(video):
    available = [h for h in (360, 720, 1080) if h <= video.height]
    return available or [video.height // 2 * 2]


def variant_width(video, height):
    return max(2, round(video.width * height / video.height / 2) * 2)


def encode(source, output, video, height, lease):
    directory = output / f"v{height}"
    directory.mkdir()
    width = variant_width(video, height)
    bitrate = 800_000 if height <= 360 else 2_500_000 if height <= 720 else 5_000_000
    args = [
        "ffmpeg", "-hide_banner", "-nostdin", "-v", "error", "-xerror",
        "-protocol_whitelist", "file,pipe", "-format_whitelist", "mov,matroska,webm",
        "-threads", "2", "-filter_threads", "2", "-i", str(source),
        "-map", f"0:{video.video_index}",
    ]
    if video.audio_index is not None:
        args += ["-map", f"0:{video.audio_index}", "-c:a", "aac", "-b:a", "128k", "-ac", "2"]
    else:
        args += ["-an"]
    args += [
        "-sn", "-dn", "-map_metadata", "-1", "-map_chapters", "-1",
        "-vf", f"scale={width}:{height},setsar=1,fps=30",
        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-preset", "fast", "-threads", "2",
        "-b:v", str(bitrate), "-maxrate", str(bitrate), "-bufsize", str(bitrate * 2),
        "-g", "120", "-keyint_min", "120", "-sc_threshold", "0",
        "-force_key_frames", "expr:gte(t,n_forced*4)",
        "-t", str(MAX_DURATION + 1),
        "-f", "hls", "-hls_time", "4", "-hls_playlist_type", "vod",
        "-hls_flags", "independent_segments", "-start_number", "0",
        "-hls_segment_filename", str(directory / "seg%05d.ts"), str(directory / "index.m3u8"),
    ]
    run_process(args, lease, timeout=JOB_TIMEOUT, output_dir=output)
    return width, bitrate + (128_000 if video.audio_index is not None else 0)


def validate_variant(directory, video, width, height, lease):
    try:
        playlist = directory / "index.m3u8"
        if playlist.stat().st_size > 1024**2:
            raise ValueError()
        lines = playlist.read_text(encoding="utf-8").splitlines()
        if (not lines or lines[0] != "#EXTM3U" or lines[-1] != "#EXT-X-ENDLIST"
                or "#EXT-X-PLAYLIST-TYPE:VOD" not in lines
                or "#EXT-X-INDEPENDENT-SEGMENTS" not in lines):
            raise ValueError()
        segments, durations = [], []
        pending = None
        for line in lines[1:]:
            lease.check()
            if line.startswith("#EXTINF:"):
                if pending is not None:
                    raise ValueError()
                pending = float(line.split(":", 1)[1].split(",", 1)[0])
                if not math.isfinite(pending) or not 0 < pending <= 4.2:
                    raise ValueError()
            elif line and not line.startswith("#"):
                if pending is None or not SEGMENT.fullmatch(line):
                    raise ValueError()
                if line != f"seg{len(segments):05d}.ts":
                    raise ValueError()
                segment = directory / line
                if not segment.is_file() or segment.stat().st_size <= 0:
                    raise ValueError()
                segments.append(segment)
                durations.append(pending)
                pending = None
            elif line.startswith(("#EXT-X-KEY", "#EXT-X-MAP", "#EXT-X-BYTERANGE")):
                raise ValueError()
        if (pending is not None or not segments or abs(sum(durations) - video.duration) > 1
                or sum(durations) > MAX_DURATION + 0.05
                or set(directory.iterdir()) != {playlist, *segments}):
            raise ValueError()
        actual = probe(segments[0], lease, segment=True)
        if (actual.width != width or actual.height != height
                or (actual.audio_index is None) != (video.audio_index is None)):
            raise ValueError()
        return playlist, segments, durations
    except (OSError, ValueError):
        raise JobError("OUTPUT_INVALID") from None


def render(source, output, lease):
    video = probe(source, lease)
    output.mkdir()
    master = ["#EXTM3U", "#EXT-X-VERSION:6", "#EXT-X-INDEPENDENT-SEGMENTS"]
    files = []
    reference = None
    for height in heights(video):
        lease.check()
        width, bandwidth = encode(source, output, video, height, lease)
        playlist, segments, durations = validate_variant(
            output / f"v{height}", video, width, height, lease)
        if reference is not None and (len(reference) != len(durations) or
                any(abs(a - b) > 0.05 for a, b in zip(reference, durations))):
            raise JobError("OUTPUT_ALIGNMENT")
        reference = durations
        # Use measured peak segment bitrate (including MPEG-TS overhead).
        bandwidth = max(bandwidth, math.ceil(max(
            s.stat().st_size * 8 / d for s, d in zip(segments, durations))))
        master += [f"#EXT-X-STREAM-INF:BANDWIDTH={bandwidth},RESOLUTION={width}x{height}",
                   f"v{height}/index.m3u8"]
        files.extend(segments)
        files.append(playlist)
    master_file = output / "master.m3u8"
    master_file.write_text("\n".join(master) + "\n", encoding="utf-8")
    files.append(master_file)  # Publish the master last.
    return max(1, round(video.duration * 1000)), files


def upload(s3, bucket, prefix, root, files, lease):
    for path in files:
        lease.check()
        with path.open("rb") as file:
            s3.put_object(Bucket=bucket, Key=prefix + path.relative_to(root).as_posix(),
                          Body=file, ContentLength=path.stat().st_size,
                          ContentType=("application/vnd.apple.mpegurl" if path.suffix == ".m3u8"
                                       else "video/mp2t"))


class Worker:
    def __init__(self, core, s3, sqs, bucket, queue_url):
        self.core, self.s3, self.sqs = core, s3, sqs
        self.bucket, self.queue_url = bucket, queue_url

    def delete(self, receipt):
        self.sqs.delete_message(QueueUrl=self.queue_url, ReceiptHandle=receipt)

    def handle(self, message):
        receipt = message["ReceiptHandle"]
        try:
            job_id = message_job_id(message["Body"])
        except (KeyError, TypeError, ValueError):
            LOG.warning("invalid queue message; left for DLQ")
            return
        status, data = self.core.post(job_id, "claim")
        if status == 204:
            self.delete(receipt)
            return
        if status != 200:
            LOG.info("claim not granted (%s)", status)
            return
        claim = Claim.parse(job_id, data)
        lease = Lease(self.core, self.sqs, self.queue_url, receipt, claim)
        lease.start()
        action, payload = "complete", {"fence": claim.fence}
        try:
            with tempfile.TemporaryDirectory(prefix="dockvue-media-") as temp:
                source, output = Path(temp) / "source", Path(temp) / "hls"
                sha = download(self.s3, self.bucket, claim, source, lease)
                duration, files = render(source, output, lease)
                upload(self.s3, self.bucket, claim.prefix, output, files, lease)
                lease.check()
                payload.update(durationMs=duration, sha256=sha)
        except LeaseLost:
            LOG.info("lease lost; result not acknowledged")
            return
        except JobError as err:
            action = "fail"
            payload.update(code=err.code, retryable=err.retryable)
        except Exception:
            action = "fail"
            # Do not include exception text: URLs and headers may contain secrets.
            payload.update(code="WORKER_IO", retryable=True)
        finally:
            lease.stop()
        if lease.lost.is_set():
            return
        status, _ = self.core.post(job_id, action, payload)
        if status == 204:
            self.delete(receipt)
        else:
            LOG.info("result not acknowledged (%s)", status)

    def run(self):
        delay = 1
        while True:
            try:
                result = self.sqs.receive_message(QueueUrl=self.queue_url,
                    MaxNumberOfMessages=1, WaitTimeSeconds=20, VisibilityTimeout=VISIBILITY)
                for message in result.get("Messages", []):
                    self.handle(message)
                delay = 1
            except Exception:
                LOG.warning("worker operation failed; message not acknowledged")
                time.sleep(random.uniform(0, delay))
                delay = min(delay * 2, 30)


def main():
    import boto3
    from botocore.config import Config

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    if sys.platform.startswith("linux"):
        # Prevent a compromised same-UID decoder reading the worker's /proc environment.
        import ctypes
        if ctypes.CDLL(None).prctl(4, 0, 0, 0, 0) != 0:  # PR_SET_DUMPABLE
            raise SystemExit("cannot protect worker process")
    token = os.environ["MEDIA_WORKER_TOKEN"]
    if not token:
        raise SystemExit("MEDIA_WORKER_TOKEN must be configured")
    region = os.getenv("AWS_REGION", os.getenv("AWS_DEFAULT_REGION", "ap-northeast-2"))
    endpoint = os.getenv("MEDIA_ENDPOINT") or os.getenv("AWS_ENDPOINT_URL")
    config = Config(connect_timeout=5, read_timeout=30,
                    retries={"total_max_attempts": 2, "mode": "standard"},
                    s3={"addressing_style": "path"} if endpoint else {})
    s3 = boto3.client("s3", region_name=region, endpoint_url=endpoint, config=config)
    sqs = boto3.client("sqs", region_name=region, endpoint_url=endpoint, config=config)
    queue = sqs.get_queue_url(QueueName=os.getenv("MEDIA_QUEUE", "dockvue-media"))["QueueUrl"]
    core = Core(os.getenv("MEDIA_CORE_URL", "http://core-api:8080/internal/media/jobs"), token)
    Worker(core, s3, sqs, os.getenv("MEDIA_BUCKET", "dockvue-media"), queue).run()


if __name__ == "__main__":
    main()
