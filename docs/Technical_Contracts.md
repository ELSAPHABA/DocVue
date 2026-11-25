# DockVue 기술적 계약 v0.2

2026-11-26 · Phase 00 설계 승인 완료. D00-01~08 권장안을 사용자가 모두 승인하였다. 후속 개발은 본 기준선을 따른다. 기능명세와 충돌하는 세부 구현은 이 문서와 ADR의 명시적 수정사항을 따른다. 런타임 호환성 테스트와 정확한 패치 pin은 Phase 01에서 수행한다.

## 0. 사용자 승인 결정 (D00-01~08)

| ID | 확정 계약 |
|---|---|
| D00-01 | Phase 05 S3 TAR/OCI archive + docker load MVP, Phase 06 S3-backed OCI Distribution Registry |
| D00-02 | 초기 DB Ubuntu EC2 자체 운영, 필요 시 비용·가용성 근거 및 승인 후 관리형 DB 전환 |
| D00-03 | 초기 컨테이너 콘텐츠 발행은 승인된 제작자로 제한 |
| D00-04 | 외부 AI 도구와 Harness 연동; 플랫폼 자체 LLM API 불필요 |
| D00-05 | Go 기반 크로스 플랫폼 DockVue CLI |
| D00-06 | linux/amd64 필수, linux/arm64는 호환성 검증 후 추가 |
| D00-07 | 동일 영상 내 발행된 Phase/Release는 동일 접근 권한; draft/정답 private staging |
| D00-08 | 사용자 PC Docker/Compose 실습, 서버 측 학습자 코드 실행 제외 |

제작자 빌드/검증용 격리 Worker는 D00-08의 제외 대상이 아니다. AWS 비용 발생 및 리소스 생성은 별도 승인 필요. 계약 변경 시 사용자 검토 및 ADR 갱신.

## 1. 기술 버전 및 의존성
| 계층 | 기준 버전/선택 | 이유 및 적용 조건 |
|---|---|---|
| JVM | Temurin Java 21 LTS, Spring Boot 4.1.x, Gradle 8.14.x wrapper | 지원 중인 Boot minor, MVC/WebFlux 동일 BOM, Jakarta 기반; 4.2 신규 계열보다 안정성 우선 |
| Persistence | Spring Data JPA/Hibernate (BOM), Flyway, MySQL 8.4 LTS | 트랜잭션·스키마 관리; Flyway MySQL 모듈 포함 |
| Reactive | Boot BOM Reactor, Reactive MongoDB/Redis, Lettuce | JDBC/JPA 금지, MVC와 독립 실행 |
| Frontend | Node 24 LTS, npm, Angular 21.2.x, TS 5.9.x, RxJS 7.8.x | 공식 Angular 21 호환 범위, LTS; strict TS |
| Learner | Vue 3.5.x, Pinia 3.x, Vue Router 4.x, Vite 8.x, HLS.js 1.x | TS 5.9 호환 검증, SPA; Safari native HLS fallback |
| Data | MongoDB 8.0.x, Redis 8.x | 보안 지원 patch, 라이선스 확인; 캐시는 권위 저장소 아님 |
| Media | FFmpeg/FFprobe 7.1+ 또는 보안 지원 8.x | 같은 바이너리 버전/빌드 옵션 pin, H.264/AAC 지원 검사 |
| Container | Docker Engine 28+ / 지원되는 Desktop, Compose v2, BuildKit, Distribution 3.x | 최소 지원 기능 검증 후 버전 확정, immutable digest |
| Infra | Ubuntu 24.04 LTS, Terraform 1.x, AWS provider 6.x, AWS SDK Java v2 | 지역 ap-northeast-2 기본; 프로덕션 배포는 별도 비용 승인 |
| CLI | Go 1.26.x 또는 착수 시 지원되는 안정 계열 | stdlib, Docker CLI/Compose 호출 + 검증용 Engine API; OS별 테스트 |
| P2 | Kotlin/Media3, Android NDK stable LTS 지원판, C++17, AngularJS 1.8.3 격리 | Android minSdk/AGP/NDK 정확한 조합 Phase 13, Angular hybrid 호환 Phase 14에서 확정 |

정확한 patch는 Phase 01에서 공식 지원·취약점·상호 호환성 확인 후 Gradle wrapper checksum, BOM, dependency locking, package-lock.json, base image digest, Terraform lockfile로 고정한다. 01 이전에는 버전 범위를 설치 명령으로 사용하지 않는다. Boot 4.1의 OpenAPI 라이브러리·Testcontainers·BlockHound 호환성을 확인하고 BlockHound 불가 시 Reactor thread assertion/JFR로 대체하며 사유 기록. P2 버전은 시점 도래 전 재검토. 라이선스(SBOM, MongoDB/Redis/FFmpeg/코덱/배포 콘텐츠 포함) 검토 필수.

공식 근거: [Spring 지원](https://github.com/spring-projects/spring-boot/wiki), [Angular 호환성](https://v21.angular.dev/reference/versions), [Angular 지원 일정](https://angular.dev/reference/releases), [Node 지원](https://nodejs.org/en/about/previous-releases), [Vite](https://v8.vite.dev/guide/), [MySQL 플랫폼](https://www.mysql.com/support/supportedplatforms/database.html), [MongoDB Ubuntu](https://www.mongodb.com/docs/v8.0/tutorial/install-mongodb-on-ubuntu/).

## 2. 서비스·프로젝트 계약
- monorepo: apps/creator-admin, apps/learner-web, services/core-api, services/realtime-api, services/media-worker, services/image-builder, services/registry-auth, tools/dockvue-cli, packages/api-contracts, packages/manifest-schema, infra/{docker,terraform,observability}, tests/{integration,e2e,load,resilience,fixtures}, docs.
- Gradle root settings + convention plugins; Java package io.dockvue.{core,realtime,media,registry}; 도메인/응용/어댑터 모듈 분리. shared DTO는 계약 스키마에서 생성/검증하되 JPA entity 공유 금지.
- core-api만 MySQL 업무 데이터 변경. worker는 내부 인증된 job claim/heartbeat/complete API를 통해 변경; realtime은 영속 이벤트 읽기/구독, Mongo append와 Redis pub/sub 담당. 서비스 간 직접 DB 갱신 금지.
- local Compose profiles: core, media, registry, observability; 기본 인프라 MySQL/Mongo/Redis 및 AWS emulator는 검증용. AWS 실동작(특히 multipart, IAM, CDN, SQS)은 별도 통합 테스트. 직접 클라우드 생성은 Phase 11 승인 후.
- API 8080, realtime 8081, CMS 4200, learner 5173, registry 5000은 로컬 기본값; DB port 외부 미노출 기본. health readiness/liveness 분리. 외부는 HTTPS 443.

## 3. 공통 API
- REST /api/v1, JSON UTF-8, camelCase, UTC RFC3339, UUID 문자열, 시간축 integer milliseconds. DB UTC datetime(6), 영상 구간 [startMs,endMs).
- [OpenAPI v1](api/openapi.yaml)은 Phase 01–10의 주요 외부 계약. 기재되지 않은 회원·관리·내부 API는 해당 개발 Phase 전에 추가. incompatible 변경은 새 major path/schema; optional field 추가 허용.
- RFC 9457 Problem Details: type, title, status, detail, instance, code, traceId, errors[]; 400 syntax, 401 invalid auth, 403 known forbidden action, 404 cross-tenant/hidden, 409 state conflict, 412 stale If-Match, 422 invalid input, 429 with Retry-After, 503 transient.
- ETag = quoted aggregate version; 변경 PUT/PATCH If-Match 필수(428 누락). tenant 조회는 항상 authorized tenantId 포함. 외부 requestId 신뢰하지 않고 서버 traceId 생성.
- cursor는 (publishedAt,id) keyset, 정렬 DESC, limit 기본 20 최대 100; 서명된 opaque cursor, tenant/filter binding. snapshot isolation 전체를 보장하지 않음.
- Idempotency-Key: create upload/video/release/job/publish/progress 등 mutation 대상, scope actor+tenant+route+key, payload canonical hash, 24h 보관 기본. 다른 payload 재사용 409; 동시 요청 in-progress 409 + Retry-After. 결과 DB 저장과 상태 전이 원자 처리; 외부 URL은 재발급 가능한 별도 endpoint.
- 모든 리소스 식별자와 클라이언트 입력 검증. 비밀·토큰·signed URL 로그 금지.

## 4. 인증/인가
- 자체 Spring Security 인증, BCrypt 또는 Argon2id(벤치 후 기본 Argon2id), 이메일 정규화/unique, 이메일 확인 전 제작 권한 없음.
- 짧은 JWT RS256 access (10분, iss/aud/sub/exp/jti), 서버 저장된 hashed opaque refresh (30일 절대 만료), refresh family rotation·재사용 검출. JWT key rollover JWKS. Web access는 메모리, refresh는 Secure HttpOnly SameSite=Lax cookie /api/v1/auth; same-origin gateway, Origin 검증 + CSRF token for cookie mutations. 외부 CLI는 Phase 08 browser device authorization 또는 짧은 일회성 코드 교환, keychain에 refresh 저장. 토큰을 URL/명령줄에 넣지 않음.
- refresh transaction row lock/CAS; client single-flight, 네트워크 응답 유실은 재로그인 허용, 이미 사용한 refresh replay 시 family revoke. logout revoke refresh family, access는 최대 TTL까지 유효. 민감 다운로드/발행은 DB 현재 권한 재확인.
- public: anonymous playback, authenticated lab download. unlisted: video link로 탐색/재생 가능(비밀보장 아님), lab download 인증 필요. private: membership 또는 명시 grant 필요. 동일 video 내 published Phase·release는 동일 권한.
- role: learner < creator < tenant_admin; platform_admin 별도 global role + 감사. private video는 해당 tenant membership 기본. 타 테넌트 ID로 조회 불가. 운영자 content read는 기본 금지, break-glass 감사.
- Redis rate limit Lua token bucket: 로그인 IP+계정 10/min, API user 120/min, credential issue 10/min 기본; login/credential 발급 Redis 장애 fail-closed, public list는 제한된 fallback.

## 5. Snapshot / Manifest / OCI
- **영상당 하나의 학습자용 논리 Repository**: videos/{tenantUuid}/{videoUuid}; 실제 이름 소문자 UUID. service별 tag r{semver}-p{ordinal}-{service}, tag는 탐색 편의만 제공. release마다 stable UUID와 semver unique.
- [Manifest JSON Schema](../packages/manifest-schema/release.schema.json)와 [예시](../packages/manifest-schema/examples/release.example.json). JSON UTF-8 고정 바이트를 SHA256/서명 검증(재직렬화 금지); YAML은 표시용. Schema validation + cross-field validator(구간·ordinal unique·service ref·상대경로·arch·digest·파일 목록·workspace/seed consistency) 필수.
- snapshot = immutable image digest/index digest + editable source workspace archive + Compose + DB fixtures + Harness + Validation + checksums. 코드 학습에는 소스·Dockerfile·빌드 도구가 반드시 포함; 읽기 전용 image만 제공하면 수정 불가이므로 workspace 복사 후 제한된 mount/rebuild 사용. 런타임 프로세스 메모리/실습자의 변경된 volume은 기본 snapshot이 아님.
- author source Git full commit SHA + build context hash. 이미지 재빌드가 bitwise 동일하다고 보장하지 않음; 배포된 digest를 고정하여 같은 artifact를 복원. base image/lockfile/seed pin. multiarch index 및 각 platform digest 검증; MVP amd64, arm64는 검증 완료 시만 광고.
- S3는 media, artifacts, registry, staging bucket/prefix 분리. Artifacts object key videos/{videoId}/releases/{releaseId}/{relativePath}, versioning, SHA256/size, immutable write conditional (If-None-Match * 지원 API 사용). manifest는 최종 생성, sha/signature 별도 trusted API record; URL 만료 10분, 필요시 재발급. ZIP/TAR path traversal/symlink/압축폭탄 차단. Registry S3 객체 직접 조작 금지.
- P0 offline archive docker save/load: archive SHA256 별도 확인, Docker image ID(config digest) 검증, RepoDigest 유무와 OCI manifest digest 혼동 금지. Compose는 검증된 로컬 tag로 매핑하고 pull_policy: never; P1 OCI pull은 repo@sha256 고정. 검증을 위해 제공한 manifest transport별 필드를 사용.
- Registry token scope는 **repository 단위**이다. 모든 학습자용 published phase/release는 같은 video 접근 정책. token은 RS256 Registry 전용 iss/aud/service, pull-only 5분; builder push 별도 identity, /v2/ challenge, token endpoint scope intersection, wildcard 금지. draft/정답/실패 이미지는 learner repo 밖의 staging repository 또는 별도 private registry에 격리.
- publish workflow: staging build/scan/restore → 승인 → 대상 repo로 copy → digest 확인 → immutable S3 manifest → SQL current pointer CAS. SQL 포인터 전환은 원자적이나 Registry/S3와 분산 원자 거래가 아님. 승인된 이미지의 promotion 중 repo token 보유자는 digest를 알면 먼저 pull할 수 있음. 따라서 비밀/정답/embargo 이미지 절대 copy 금지; 강한 릴리스별 embargo/회수 요구가 생기면 별도 repo 또는 정책-aware proxy ADR 필요. 실패 시 이전 pointer 유지; 이미 copy된 승인 blob은 orphan reconciliation. 기존 token/다운로드는 즉시 회수 불가.
- release states DRAFT → BUILDING → VALIDATING → READY → PUBLISHING → PUBLISHED; FAILED/retry는 새 attempt, REVOKED는 별도 상태/접근정책(artifact 수정 금지). published pointer rollback은 이전 검증된 release를 CAS로 선택; 현재 release 삭제 불가. soft deletion + 참조 graph, 30일 최소 보존 기본; GC는 write 중단/read-only + backup + dry run + 모든 retained digest reachability 검증.
- 근거: [Distribution scope](https://distribution.github.io/distribution/spec/auth/scope/), [token auth](https://distribution.github.io/distribution/spec/auth/token/), [S3 driver](https://distribution.github.io/distribution/storage-drivers/s3/), [Compose trust](https://docs.docker.com/compose/trust-model/).

## 6. 작업·이벤트
- SQS Standard queue 분리 media/build, DLQ 각 1개, long poll 20s, retention 4일/DLQ 14일, maxReceiveCount 5. initial visibility 120s, heartbeat 30s, visibility extend 120s; SQS 최대 12h 고려해 작업 timeout 2h 기본. 제한 초과 작업 분할/취소. retry exponential jitter; terminal input error는 재시도 금지.
- 업무 상태와 outbox insert 같은 MySQL tx. relay claim SKIP LOCKED + lease 후 tx 종료; SQS send 후 sent 상태 갱신. send 후 crash 재전송 가능. eventId unique, consumer claim+state CAS/lease+monotonic fence, completion 조건 attempt/fence 일치, 작업 output attempt prefix. 늦은 worker의 결과는 publish 금지. DB 상태 반영 뒤 message delete. exactly-once delivery 주장 금지.
- Job QUEUED → RUNNING → SUCCEEDED/FAILED/CANCELLED, transient failure는 RETRY_WAIT → QUEUED. deadline/lease sweeper; Retry API는 실패한 job에서 새 attempt, 최대 5 기본. SQS DLQ와 DB job 상태는 주기적 reconcile. idempotent completion.
- [Event schema](api/event.schema.json): eventId, eventType, schemaVersion, aggregateId, aggregateVersion, tenantId, occurredAt, traceId, payload. 같은 aggregate의 version은 상태 tx에서 증가, 전역 순서 보장 없음; 소비자는 duplicate/older drop, gap 시 state API.
- WebFlux: Redis pub/sub은 알림, MySQL job state 및 outbox에 근거한 durable read; Mongo append events with unique eventId. SSE는 per-job version을 ID로 사용, Last-Event-ID 이후 durable replay, gap/replay window 만료 시 snapshot; subscribe-before-query + bounded buffer + dedup, heartbeat 15s, reconnect jitter. 상태 최신값 coalesce 허용, 채팅은 durable sequence/cursor replay. per-client 256 event buffer, overflow disconnect/replay; 최대 payload 64 KiB. WebFlux loop에서 blocking 금지.
- 근거: [SQS visibility](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-visibility-timeout.html).

## 7. VOD 계약
- upload max 5 GiB, duration 4h, part 16 MiB(마지막 제외 S3 최소 5 MiB), 4 parallel, signed part TTL 15분. resume는 ListParts + server session ownership. 서버 complete multipart; S3 HeadObject/checksum verify, ETag를 SHA256으로 해석하지 않는다. multipart SHA256 composite와 full-file SHA256 구별: S3 algorithm/type 명시, part checksum 검증; worker streaming SHA256 full bytes 별도 계산/대조. 서버 checksum 검증 후 업로드 완료. [AWS checksum](https://docs.aws.amazon.com/AmazonS3/latest/userguide/checking-object-integrity-upload.html).
- input allowlist MP4/MOV/WebM, 실제 FFprobe 검증; duration/size 제한, untrusted ffmpeg isolated, no network/protocol allowlist. H.264 AVC/AAC, 360/720/1080p 원본 이하, 4초 target HLS segment, aligned keyframes, VOD playlist ENDLIST. attempt prefix → HLS validate → DB pointer. public/private origin S3 OAC, CloudFront signed cookie (10분, private path policy), no direct S3 access. 권한 변경 이후 기존 쿠키는 TTL 동안 유효. video 원본 버전·duration은 Release에 고정; 교체 시 Phase 시간 범위 재검증, 기존 release 재생은 이전 HLS retained.
- playlist, chapter markers, HLS.js Safari fallback; progress throttled 15초 + pause/end best effort, last position bounded.

## 8. CLI / Harness / validation
- commands login,pull,start,status,verify,export,reset,stop; explicit --release or resolve current once then persist releaseId/digest. workspace path per user/video/release/phase, unique project name using short hash+instanceId; no arbitrary host mounts. docker compose v2 CLI for orchestration (not reimplement Compose), process argv no shell interpolation; OS signal/context cancellation, atomic download temp+rename, disk checks, per-workspace lock. exit 0 success, 2 usage, 3 auth, 4 integrity, 5 environment, 6 verification failure, 7 network, 8 policy rejection.
- download → signature/checksum → validate normalized Compose (including interpolation; no extends/include/build context escape/host namespaces/devices/socket/privileged; local build only approved workspace) → explicit review → pull/load → initialize fresh volume/seed → health → work/verify.
- verify runs specified argv inside named service without host shell; timeout 120s default, bounded stdout/stderr (1 MiB), JSON result status/checks/duration, no raw source/log upload without opt-in. Author tests may be untrusted; run with same sandbox limits. reset confirms scope, export backup first optional, removes only matching labeled project resources. no global prune.
- Harness required AGENTS.md + CONTEXT.md + OBJECTIVES.md + TOOLS.md + HINTS.md + VALIDATION.md. Coach default, Pair/Auto explicitly chosen; prompts cannot grant capabilities. external AI tool executes within its own permission model; no mandatory AI API subscription, key storage, or proxy in MVP. Phase start bundle contains no hidden answer; completed examples separately authorized. Progress is self-reported, not certification.

## 9. Quality & default limits
- initial caps: 20 phases/video, 1 GiB compressed bundle (media/OCI archive separately), 5 GiB total compressed image archive per phase, 4 CPU/8 GiB memory/20 GiB lab suggested; limits configurable, actual per phase requirements recorded.
- SLO candidates: API p95 <=300ms under recorded 100 virtual user workload, VOD startup p95 <=3s in defined network, SSE p95 <=1s, clean restore 100% supported matrix, no duplicate published release, tenant isolation 100%. No measurements yet.
- log correlation traceId/eventId/jobId, JSON, no secrets. Prometheus/Grafana + CloudWatch; alert DLQ>0, outbox age>60s, worker failure, disk>80%; adjust after baseline. backups RPO 24h/RTO 4h initial targets, restore drill before public.
- D00-01~08 승인 완료. 정확한 patch 및 정식 스키마/빌드 검증(Phase 01), 지원 OS 검증(Phase 08), 실제 AWS 예산/배치/복구 검증(Phase 11)은 미수행. No AWS spend/deployment or feature code in Phase 00.
