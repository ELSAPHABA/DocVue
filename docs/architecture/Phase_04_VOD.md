# Phase 04 — VOD streaming pipeline

기준 HEAD b7d2260, Phase 03 CI 4/4 확인. 범위 DV-VID-01–08, DV-AUTH-05. Playwright headless 전용. 실제 AWS 리소스 생성/비용 없음. 영상 파일과 HLS는 로컬 S3 emulator에서 검증하며 실제 Release 발행은 Phase 05–07, 실제 CloudFront OAC/Signed Cookie는 Phase 11에서 검증한다.

## 구현 구조

~~~mermaid
sequenceDiagram
 participant C as Creator CMS
 participant A as Core API
 participant D as MySQL
 participant S as S3
 participant Q as SQS
 participant W as Media Worker
 C->>A: create upload (idempotency key, full SHA256)
 A->>D: reserve upload/media
 A->>S: CreateMultipartUpload
 C->>A: ListParts / sign part
 C->>S: presigned PUT + SHA256
 C->>A: complete
 A->>S: ListParts, Complete, HEAD
 A->>D: UPLOADED + job + outbox transaction
 A->>Q: relay (at least once)
 Q->>W: transcode event
 W->>A: claim, fence/lease
 W->>S: streamed original + checksum
 W->>W: sandbox FFprobe/FFmpeg HLS
 W->>S: attempt prefix / master last
 W->>A: complete with fence + actual duration
 A->>D: atomic READY / current media pointer
 W->>Q: delete after acknowledgement
~~~

- V4 migration: media_versions, upload_sessions/parts, jobs, outbox_events, playback_progress. videos.current_media_version_id composite FK. Core API만 MySQL 변경. 기존 V1–V3 수정 없음.
- Java AWS SDK v2 BOM 2.31.78 + Gradle locks; Python worker boto3 pinned dependencies, FFmpeg/FFprobe 7.1.5 on Debian trixie. 브라우저 HLS.js 1.6.15 lazy import, @noble/hashes 2.0.1 incremental SHA-256.
- 5 GiB/4시간 입력 제한, MP4/MOV/WebM. 16 MiB parts, 마지막 파트 제외, 최대 320 parts, 4 parallel; presigned part 15분. Full SHA256 hex는 브라우저에서 4 MiB 단위 계산. Part SHA256 base64, S3 ListParts와 HEAD checksum/size/ETag(해시로 해석하지 않음). Multipart composite = SHA256(concatenated part digests); worker는 원본 full SHA256을 독립적으로 확인. LocalStack의 checksum metadata 미제공 시 로컬 개발에 한해 선언된 part checksum 사용, worker full hash 검증은 생략하지 않는다.
- actor/tenant/video/request-key scope의 upload reservation. Remote S3 호출은 DB lock 밖에서 수행. Idempotent complete, response-loss recovery via HEAD; 만료 24h, 알려진 upload abort 및 lifecycle rule. 실패/고아 object 삭제는 별도 retention/GC 정책 필요. 이전 media pointer는 새 변환이 실패하면 유지.
- SQS Standard, outbox relay SKIP LOCKED, lease, send 이후 SENT update. Worker claim 120s lease, 30s heartbeat/visibility extension, fence 증가, attempt-specific HLS prefix, retry jitter/backoff 및 최대 5 attempts/2h deadline. 실패 상태/lease sweep. 정확히 한 번의 전달이 아니라 멱등 상태 전이. 실제 DLQ 장기 reconciliation 및 운영 관측은 후속 운영 단계.
- Worker non-root UID 10001, no DB access, read-only root, dropped capabilities, no-new-privileges, resource limits, local protocol allowlist + Linux seccomp network denial for decoder, minimal environment. 임시 작업 공간은 disk-backed volume, 입력+출력 최대 29 GiB 소요 가능. 360/720/1080p 원본 이하, 작은 원본 fallback, H.264/AAC(오디오 없는 입력은 video-only), 4초 HLS 세그먼트, aligned GOP, validation, master last. 실제 duration으로 Phase 범위를 검증 후 pointer 전환하며 이후 draft duration은 임의 변경 금지.
- Phase 03 명시적 미리보기 상태에서만 준비 완료된 VOD를 재생할 수 있다. PUBLISHED release를 생성하지 않는다. PUBLIC/UNLISTED/PRIVATE의 기존 접근 정책 재사용. Local only: 10분 HMAC HttpOnly cookie video/media/actor scope, playlist 요청마다 최신 접근 권한 재확인, segment 60초 이내 presigned GET redirect (Core 대용량 데이터 proxy 없음). 원본/실패 output prefix 접근 불가. 실제 CDN Signed Cookie/OAC 및 IAM은 Phase 11 승인 후 구현·검증. 운영 모드의 local playback은 fail closed (CDN_NOT_CONFIGURED).
- playback_progress는 user/video/media별 last position bounded; preview_progress와 분리. Vue는 15초 throttle 및 pause/end, phase seek, playback rate, quality 선택, native HLS fallback. 실제 progress는 release-bound 검증 진도와 구별한다.

## API

OpenAPI 0.4.0. 관리 경로 /api/v1/tenants/{tenantId}/videos/{videoId}/uploads (POST), /uploads/{uploadId} (GET/DELETE), /parts (POST), /complete (POST), /media (GET), /media/retry (POST). 학습자 /api/v1/videos/{videoId}/playback (GET), /stream/master.m3u8 및 /stream/{variant}/{file}, /playback-progress (GET/PUT). 내부 /internal/media/jobs/{jobId}/{claim,heartbeat,complete,fail}는 worker shared secret 인증, frontend proxy에 포함하지 않는다.

## 실행

~~~powershell
conda activate HongDev
docker compose --profile core --profile media up -d --build --wait
npm ci
npm run dev:admin
# 별도 터미널: npm run dev:learner
~~~

기존 .env 포트 유지, MEDIA_BROWSER_ENDPOINT는 브라우저가 접근 가능한 S3 endpoint (기본 loopback LocalStack host port); 격리 Docker E2E는 http://localstack:4566 사용. MEDIA_WORKER_TOKEN은 local-only 기본값, 운영 Secret으로 대체. LocalStack persistence/IAM/CORS 동작은 실제 AWS와 동일하다고 주장하지 않는다.

## 검증

~~~powershell
conda run -n HongDev .\gradlew.bat --no-daemon check
conda run -n HongDev npm run build
conda run -n HongDev npm test
conda run -n HongDev python scripts/validate-contracts.py
conda run -n HongDev python -m unittest discover -s services/media-worker -v
~~~

Playwright: headless:true, Chromium, 격리 Compose 프로젝트 dockvue-phase04-test 또는 CI. scripts/generate-media-fixture.py로 10초 720p/오디오 합성 영상 생성 후 유효한 MP4 free atom으로 17 MiB를 만들어 두 파트 업로드 검증. 실제 UI, S3 ListParts, FFmpeg HLS, 미디어 버전, 중복 job claim, 접근 권한 및 seek/progress. test-results/와 playwright-report/는 커밋하지 않는다.

### 2026-11-26 로컬 증거

| 검증 | 실제 결과 |
|---|---|
| HongDev Gradle Core check/bootJar, Docker Core build | exit 0 |
| HongDev npm production build + test | exit 0; Angular 1/1, Vue 8/8 (VOD 신규 4개 포함) |
| OpenAPI/JSON Schema validator | exit 0 |
| 격리 DB Flyway V1–V4 | success=1, V4 적용 |
| Docker 내부 Playwright media.spec.ts | exit 0, 1/1, 시나리오 44.9초, 전체 1.2분; 실제 17 MiB 2파트, 재개, FFmpeg 360/720p HLS, 재생/탐색/위치, private denial |
| Docker 내부 Playwright content.spec.ts | exit 0, 1/1, 시나리오 15.1초, 전체 53.4초 |
| Worker FFmpeg 7.1.5 테스트 | 12/12 (실제 HLS 및 WebM, sandbox 포함) |
| 인증/RBAC/Redis 회귀 | 11개 검증 그룹 및 별도 Redis outage fail-closed 통과 |
| 실제 SQS 중복 메시지 fault injection | 큐 visible/in-flight 0/0, DB SUCCEEDED/attempt=1/fence=1 유지 |
| 일반 개발 환경 복원 | 기존 MySQL volume 유지, Flyway 1–4 success=1, 7개 기존 서비스 healthy + Worker running, smoke 11/11, 두 frontend HTTP 200 |

Worker 최종 소스 반영 재빌드 후 컨테이너 테스트 12/12 재통과(13.189초). 격리 프로젝트의 테스트 리소스만 down -v로 정리했다.

1080p 및 대용량/4시간 최대 입력의 실측 부하, Windows 호스트 전체 E2E, Safari 실제 기기, 실제 AWS CDN/IAM 및 운영 DLQ/복구는 미검증이다. Vue HLS lazy chunk 507.66 kB에 대한 Vite 크기 경고는 비치명적이며 이후 성능 최적화에서 측정한다. [TS-04](../troubleshooting/TS-04-vod-duplicate-delivery.md) 참고.
