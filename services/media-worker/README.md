# Media Worker

Phase 04: SQS worker for fenced VOD jobs. The worker accesses only the Core internal API, SQS and S3; Core owns database state and publishes the successful attempt.

Build from the repository root:

```sh
docker build -f infra/docker/media.Dockerfile -t dockvue-media-worker .
python -m unittest discover -s services/media-worker -v
```

The container runs as UID/GID 10001. FFmpeg and FFprobe accept only local file/pipe protocols and approved demuxers, with no inherited AWS or worker credentials. A Linux seccomp filter denies network socket operations in both decoder subprocesses. The worker streams the original to temporary storage and checks the declared size and full SHA-256 before encoding. It enforces the 5 GiB input, 4-hour duration and 2-hour job limits; allow up to 29 GiB of temporary storage for source and encoded files. Encoded H.264/AAC VOD variants use 4-second segments and aligned keyframes. Segments and variant playlists upload to the claim's attempt prefix before the master playlist and fenced completion request.

Environment:

| Variable | Default / purpose |
| --- | --- |
| MEDIA_WORKER_TOKEN | Required shared Core worker credential |
| MEDIA_CORE_URL | http://core-api:8080/internal/media/jobs |
| MEDIA_ENDPOINT | Optional S3/SQS endpoint (LocalStack); AWS_ENDPOINT_URL fallback |
| AWS_REGION | AWS_DEFAULT_REGION or ap-northeast-2 |
| MEDIA_BUCKET | dockvue-media |
| MEDIA_QUEUE | dockvue-media |

Use the AWS SDK credential chain (or LocalStack test credentials in development). Receive one message at a time, long poll for 20 seconds, and extend Core and SQS leases every 30 seconds. A stale claim or unacknowledged completion is left for redelivery. Only a terminal claim or acknowledged complete/fail triggers message deletion. Retryable failures are rescheduled by Core, not the worker.
