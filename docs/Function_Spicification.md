# DockVue 기능명세서

> 문서 경로: docs/Function_Spicification.md (요청된 파일명 유지)
>
> 상태: 설계 초안 · 구현 전 / 버전: 0.1.0 / 기준: 2026-11-21
>
> 제품명은 **DockVue**이며, GitHub 저장소명 **DocVue**는 그대로 유지한다. 본 문서는 현재 동작을 보증하는 사용 설명서가 아니라 개발 계약과 검증 기준을 정의한다.

## 1. 제품 개요

**DockVue**는 동영상의 각 Phase에 재현 가능한 Docker 실습 환경(OCI 이미지·Compose·DB Seed), 버전 고정된 AI 하네스 및 자동 검증 도구를 연결하는 실습형 동영상 학습 플랫폼이다.

- 핵심 단위: **Video 1개 → 논리적 OCI Repository 1개 → Phase N개 → Phase별 불변 Snapshot + Harness + Validation**.
- 학습자는 임의의 Phase에서 시작하여 영상의 해당 시점과 동일한 출발 환경을 자신의 컴퓨터에 다운로드·복원·실습·검증할 수 있다.
- 제작자는 영상, Phase, 컨테이너 빌드 원본, 하네스, 검사 규칙을 작성하고 발행 전 자동 재현성 테스트를 통과시킨다.
- S3는 영상/HLS·OCI Registry의 원본 레이어·Phase 번들/하네스/데이터 Fixture의 영속 저장소로 사용한다.
- 제품은 일반 영상 플랫폼이 아닌 **실행 가능한 학습 환경의 배포·버전 관리·재현성**을 주요 차별점으로 삼는다.

### 1.1 개발 목표 및 비목표

| 항목 | 목표 | 비목표 / 범위 제한 |
|---|---|---|
| 콘텐츠 | VOD, Phase 마커, 재생목록, 검색, 권한 | 초기 단계의 자동 영상 생성 |
| 실습 | Docker/Compose 환경 복원, AI 하네스, 검증, 초기화 | 기본 MVP에서 서버 측 임의 코드 실행 |
| 저장/배포 | S3 + OCI Registry + 불변 Manifest | S3 객체 키를 Docker Registry API처럼 직접 노출 |
| 학습 | Phase별 독립 시작, 선택적 진행 이력, 힌트 모드 | AI가 무조건 정답을 작성하는 자동 완주 |
| 운영 | 실패 복구, 관측, 백업, 비용 제한 | 초기부터 대규모 Kubernetes 클러스터 |
| 확장 | 라이브·채팅, Android NDK 분석, 레거시 UI 현대화 | 위 확장이 MVP 출시의 선행조건이 되는 것 |

### 1.2 기능 우선순위

- **P0 / 필수 MVP**: 제작자 VOD 업로드, Phase 편집, 단일 영상용 Repository/릴리스, S3 아티팩트 저장, 로컬 Docker Compose 실행, Harness 다운로드, 검증, 권한, 기본 배포/관측.
- **P1 / 정식 제품**: S3-backed OCI Registry, 자체 CLI, 다중 컨테이너 Phase, 자동 CI 빌드·검증·발행, 진행률, WebFlux 상태 알림, 관리자 운영 기능.
- **P2 / 확장**: 라이브 HLS·OBS, WebSocket 채팅, Android Media3+NDK, AngularJS 전환 실험, 시간 동기화 텔레메트리, AI 도구 연동 자동화·협업.

## 2. 사용자 및 접근 권한

| 역할 | 주요 권한 |
|---|---|
| Guest | 공개 영상/소개 열람; 비공개 Phase 패키지 접근 불가 |
| Learner | 허용된 영상 재생, Phase 다운로드, 로컬 검증, 본인 진도 관리 |
| Creator | 본인 채널의 영상·Phase·하네스·빌드·릴리스 관리 |
| Tenant Admin | 채널 구성원, 콘텐츠 공개 범위, 정책·사용량 관리 |
| Platform Admin | 빌드 큐, Registry, 취약점·운영 이벤트, 사용량·장애 관리 |

- 다중 테넌트: Tenant → Membership/Role → Video/Repository/Release.
- 권한은 UI 노출뿐 아니라 API, S3 번들 배포, Registry Pull 토큰, CDN 재생까지 서버에서 강제한다.
- Creator가 발행한 이미지/하네스는 학습자 PC에서 실행되므로 **제작자는 안전하지 않은 코드를 제공할 수 있는 공급자**로 간주하고 신뢰 수준/검사 상태를 표시한다.

## 3. 핵심 도메인 및 불변식

### 3.1 엔터티

| 엔터티 | 핵심 필드 |
|---|---|
| Tenant | id, name, plan, quota_policy |
| User / Membership | user_id, tenant_id, role, status |
| Video | id, tenant_id, title, visibility, source_key, hls_prefix, status, duration_ms |
| Phase | id, video_id, ordinal, start_ms, end_ms, title, objective |
| VideoRepository | video_id(UNIQUE), registry_name, status |
| Release | id, video_id, semver, status, manifest_key, manifest_sha256, published_at |
| PhaseArtifact | release_id, phase_id, images[digest], compose_key, seed_keys, harness_key, verify_key |
| UploadSession | id, owner_id, object_key, upload_id, checksum, status |
| BuildJob / TranscodeJob | id, source_revision, idempotency_key, state, retry_count |
| OutboxEvent | event_id, aggregate_id, type, payload, delivery_state |
| Enrollment / Progress | learner_id, video_id, phase_id, validation_state, last_position_ms |
| AuditEvent | actor, action, target, occurred_at, request_id |

### 3.2 불변식

1. Video 하나는 **하나의 논리적 OCI Repository**에 매핑된다. Repository는 복수의 서비스 이미지(Tag/Digest)를 포함할 수 있다.
2. Phase는 반드시 한 Video에 귀속되며 순서와 시간 범위를 갖는다. 허용된 겹침 정책을 사전에 정하고 기본은 중첩 금지이다.
3. Snapshot은 **Image Digest + Compose + Fixture + Harness + Validation + Manifest**의 묶음이다. 실행 중 프로세스 메모리 스냅샷을 의미하지 않는다.
4. Release는 발행 이후 변경 불가(immutable). 수정 시 새 Release를 만들며, 영상의 current_release 포인터만 원자적으로 전환한다.
5. Phase N의 시작 환경은 독립 복원이 가능해야 한다. Phase N-1의 로컬 완료 상태에 의존하지 않는다.
6. Container Image는 Volume 데이터를 자동 포함하지 않는다. DB 초기 데이터와 마이그레이션을 명시적으로 보관한다.
7. Harness 버전과 이미지 Digest 및 검증 코드 버전을 Release Manifest에서 함께 고정한다.
8. S3/Registry 객체 삭제는 Release 참조·보존 정책 확인 후에만 수행한다.

## 4. 사용자 기능 요구사항

### 4.1 인증·테넌트·채널 (P0)

- DV-AUTH-01: 회원 가입·로그인·로그아웃·토큰 갱신; 비밀번호 해시 및 세션/토큰 만료 처리.
- DV-AUTH-02: Tenant/채널 생성, 사용자 초대, Creator/Learner 역할 부여 및 변경 감사 로그.
- DV-AUTH-03: Video·Release·Bundle·Registry에 대한 테넌트 경계 검사.
- DV-AUTH-04: Public/Unlisted/Private 영상. Unlisted는 URL 보유만으로 접근 가능한 정책인지 별도 명시하며, Private는 인증 강제.
- DV-AUTH-05: Presigned URL/Registry 토큰의 짧은 수명과 최소 범위, 권한 취소 후 신규 발급 거절.

### 4.2 영상 업로드·변환·배포 (P0)

- DV-VID-01: 파일 검증(용량, 형식, 길이, MIME/실제 코덱, 체크섬) 후 S3 Presigned Multipart Upload 세션 발급.
- DV-VID-02: 프런트엔드가 S3로 직접 업로드, 완료 요청 시 서버가 S3 객체의 크기/ETag·체크섬을 확인한다. 중단된 Multipart Upload는 만료/정리한다.
- DV-VID-03: MySQL 트랜잭션으로 UPLOADED 상태와 Outbox 이벤트를 기록; Outbox Relay가 SQS 전송.
- DV-VID-04: FFprobe/FFmpeg Worker가 원본을 검증하고 HLS Master Playlist·다중 화질(원본 이하 360/720/1080p 등)을 생성한다.
- DV-VID-05: GOP/Keyframe 정렬, 세그먼트 길이, 오디오 유무, 호환 코덱, 실패 시 재처리 정책을 검사한다.
- DV-VID-06: HLS 결과를 S3에 저장하고 CloudFront로 제공한다. 비공개 VOD에는 OAC + Signed Cookies/URL의 접근 정책을 적용한다.
- DV-VID-07: UI는 변환 진행·실패 원인·재시도·미게시 상태를 표시한다. 준비가 완료되지 않은 영상은 발행할 수 없다.
- DV-VID-08: Vue 플레이어는 Phase 마커, 속도·화질 선택, 이어보기, 네트워크 오류 표시, 모바일 레이아웃과 키보드 접근성을 제공한다.

### 4.3 Phase 타임라인 및 제작자 CMS (P0)

- DV-PHS-01: 영상 내 Phase 생성/수정/삭제/정렬, start_ms/end_ms 설정, 미리보기 및 경계 검증.
- DV-PHS-02: 각 Phase에 제목, 목표, 선수 지식, 예상 소요, 시작 상태·완료 기준을 설정한다.
- DV-PHS-03: Angular Reactive Forms + RxJS로 비동기 검증, 저장 중 중복 요청 차단, 서버 낙관적 잠금 버전 충돌 처리.
- DV-PHS-04: Phase별 Compose, 환경 변수 템플릿, Fixture, Harness 문서, Validation 명령, 권장 자원을 연결한다.
- DV-PHS-05: 제작자는 한 Phase의 패키지를 로컬에서 미리 받아 재현 테스트할 수 있다.
- DV-PHS-06: 영상 재생 시 타임라인 Phase 표시 및 해당 시점의 환경 준비 동작 제공.
- DV-PHS-07: 같은 영상의 Release 버전별 Phase 구성을 확인하고 특정 Release로 돌아갈 수 있다.

### 4.4 컨테이너 Repository/스냅샷 (P0→P1)

- DV-OCI-01: Video 생성 시 논리 Repository 식별자를 예약한다(예: registry.dockvue.example/videos/video-id).
- DV-OCI-02: Phase는 하나 또는 여러 서비스 이미지를 참조할 수 있다. 단일 OCI Repo 안에 이미지별 구분 Tag를 사용하고 발행 시 Digest로 고정한다.
- DV-OCI-03: Dockerfile/Build Context/Compose는 버전 관리된 Git 원본에서 읽으며, 임의의 라이브 컨테이너 commit을 표준 배포 방식으로 사용하지 않는다.
- DV-OCI-04: OCI Distribution Registry가 Docker Registry API를 제공하고 S3 드라이버에 레이어/Manifest를 저장한다. S3를 직접 Docker Pull endpoint로 사용할 수 없다.
- DV-OCI-05: 로컬 PoC에서는 S3에 OCI archive/TAR을 보관해 다운로드 후 docker load 하는 대체 경로를 허용한다. 정식 배포는 Registry Pull을 우선한다.
- DV-OCI-06: 동일 레이어 재사용, 큰 이미지 다운로드·재시도·체크섬 검증, Registry Garbage Collection과 참조 보존 정책을 검증한다.
- DV-OCI-07: 지원 플랫폼(linux/amd64 필수, linux/arm64 권장) 및 최소 CPU/RAM/Disk/Docker Compose 버전을 Manifest에 기록한다.
- DV-OCI-08: Phase별 DB Seed는 별도 Fixture로 관리하며 초기화 순서, 마이그레이션 버전, Volume 정책을 명시한다.

### 4.5 AI Harness (P0 문서, P1 연동)

- DV-AI-01: Phase별 Harness bundle은 AGENTS.md(진입), CONTEXT.md, OBJECTIVES.md, TOOLS.md, HINTS.md, VALIDATION.md를 포함한다.
- DV-AI-02: Harness에 코드·서비스 토폴로지, 실습 목적, 허용/금지된 명령 범위, AI가 답을 제공하는 정도, 검증 기준, 실패 시 복구 방법을 명시한다.
- DV-AI-03: Harness와 이미지·Seed·Validation 버전은 한 Manifest로 묶고 SHA-256 무결성을 확인한다.
- DV-AI-04: Coach(힌트 중심), Pair(코드·디버깅 협업), Auto(명시적 승인 범위에서 실행) 모드 제공. **정책 문서만으로 명령 실행 권한이 생기지 않는다.**
- DV-AI-05: 사용자가 선택한 AI 도구가 로컬 Harness를 읽도록 경로와 로딩 방법을 안내한다. 지원하지 않는 AI 도구에는 일반 텍스트 문서로 제공한다.
- DV-AI-06: AI 에이전트에 호스트 Docker Socket, 관리자 권한, 외부 비밀정보를 기본 제공하지 않는다. 외부 Harness는 명령이 아닌 신뢰되지 않은 콘텐츠로 취급한다.
- DV-AI-07: 하네스 수정은 기존 Release를 덮어쓰지 않고 새 Release로 발행한다.

### 4.6 다운로드·로컬 실행·검증 (P0→P1)

- DV-CLI-01: 학습자는 웹에서 특정 Phase 및 Release를 선택하여 Manifest/하네스·환경 준비 절차를 확인한다.
- DV-CLI-02: MVP는 ZIP 번들 + docker compose / docker pull 명령 제공. P1에는 dockvue CLI로 통합한다.
- DV-CLI-03: CLI는 인증, 지원 OS/Arch·디스크·메모리·Docker/Compose 버전, 포트 충돌, 권한 정책을 사전 검사한다.
- DV-CLI-04: 이미지 Pull은 Registry 인증을 이용하며 버전 Tag 확인 후 Digest로 검증한다. 비밀 토큰을 로그나 명령행 인수에 출력하지 않는다.
- DV-CLI-05: Phase별 격리된 디렉터리/Compose Project Name/Network/Volume을 사용하여 실습 간 충돌을 방지한다.
- DV-CLI-06: 재시도 가능한 pull/start/stop/status/verify/reset/cleanup 절차. reset은 데이터 삭제 범위·백업 선택을 표시하고 명시적 확인을 받는다.
- DV-CLI-07: 검증은 허용된 테스트 명령으로 구현하며 Exit Code, JSON 요약, 로그 증거를 제공한다.
- DV-CLI-08: 결과 업로드는 사용자 동의를 얻어 필요한 최소한의 점수/상태만 전송한다. 로컬 검증 결과는 위조 가능하며 공식 자격 평가용 증빙으로 간주하지 않는다.
- DV-CLI-09: 독립 Phase 시작이 기본이며, 사용자의 로컬 수정 사항을 다음 Phase로 자동 덮어쓰지 않는다. 명시적 Export/Backup을 지원한다.
- DV-CLI-10: CLI 예시 인터페이스(구현 전):
  - dockvue login
  - dockvue pull video-id --phase 3 --release 1.0.0
  - dockvue start video-id --phase 3
  - dockvue status video-id --phase 3
  - dockvue verify video-id --phase 3
  - dockvue reset video-id --phase 3
  - dockvue stop video-id --phase 3

### 4.7 학습·검색·운영 (P0→P1)

- DV-LRN-01: 영상 제목/태그/기술 스택/난이도 필터 및 Cursor Pagination.
- DV-LRN-02: 재생 위치 저장, Phase 진입 기록, 본인 검증 이력, 이어보기.
- DV-LRN-03: Phase 검증 실패 시 실패 위치, 재현 명령, 관련 힌트 문서 제공. 실제 답안 자동 노출 금지.
- DV-OPS-01: Admin에서 영상/트랜스코딩/빌드/발행 상태 및 최근 실패 작업 표시.
- DV-OPS-02: 권한 변경, 릴리스 전환, 이미지 폐기, 재시도 요청을 Audit Log로 남긴다.
- DV-OPS-03: 테넌트별 저장 공간, 영상 전송량, Registry Pull, Build 비용의 예산/쿼터 관리.

### 4.8 후속 확장 (P2)

- OBS → RTMP(SRS 또는 유사 미디어 오리진) → HLS 라이브 송출; 채팅(WebFlux WebSocket, Redis Pub/Sub, MongoDB 기록), Live DVR 주석.
- Android Media3 플레이어 + C++/JNI Ring Buffer/EWMA·버퍼링 분석 모듈; 네이티브 기능 장애 시 기본 재생 유지.
- AngularJS 구형 CMS를 제한된 환경에서 별도 구현하고 Angular로 점진 전환하는 마이그레이션 실험.
- 영상 시각과 로그·메트릭·센서 데이터 시간축을 연결하는 Timeline Sync/IncidentReplay 기능; P0 스코프에 포함하지 않음.
- 상호작용형 분기 영상, AI 자동 실습 보조, 라이브 협업은 별도 ADR 및 보안 검토 후 착수.

## 5. 논리 아키텍처

~~~mermaid
flowchart TB
  subgraph Clients["Clients"]
    AC["Angular Creator CMS"]
    VP["Vue Learner Portal"]
    CLI["DockVue CLI / Local Docker"]
    AND["Android (P2)"]
  end
  subgraph App["AWS / Ubuntu"]
    EDGE["HTTPS Gateway / Auth"]
    CORE["Spring MVC Core API"]
    RT["Spring WebFlux Realtime API"]
    PUB["Outbox Relay"]
    MEDIA["FFmpeg Worker"]
    BUILD["Isolated Build + Validation Worker"]
    OCI["OCI Distribution Registry"]
  end
  subgraph Storage["Data & Storage"]
    SQL[("MySQL")]
    MONGO[("MongoDB")]
    REDIS[("Redis")]
    SQS["SQS + DLQ"]
    VOD[("S3 VOD/HLS")]
    ART[("S3 Bundles/Seeds/Harness")]
    LAYER[("S3 Registry Layers")]
    CDN["CloudFront"]
  end
  AC --> EDGE
  VP --> EDGE
  CLI --> EDGE
  AND --> EDGE
  EDGE --> CORE
  EDGE --> RT
  CORE --> SQL
  CORE --> PUB
  CORE --> REDIS
  RT --> MONGO
  RT --> REDIS
  PUB --> SQS
  SQS --> MEDIA
  SQS --> BUILD
  MEDIA --> VOD
  BUILD --> ART
  BUILD --> OCI
  OCI --> LAYER
  VOD --> CDN
  CDN --> VP
  CLI --> OCI
  CLI --> ART
~~~

- 실제 배포에서는 영상 HLS의 CloudFront 배포 경로, Registry의 TLS/Auth endpoint, 번들 S3 서명 URL 경로를 별도 도메인/정책으로 구분한다.
- API 서버는 큰 영상/이미지 바이트를 프록시하지 않는다. 미디어는 CDN, 이미지 레이어는 Registry, 번들은 서명 URL로 제공한다.
- 관리 트랜잭션은 Spring MVC + MySQL/JPA, 지속 연결·이벤트 전파는 WebFlux + Reactive MongoDB/Redis로 처리하여 Event Loop Blocking을 방지한다.

## 6. 파이프라인 워크플로

### 6.1 영상 업로드·HLS 발행

~~~mermaid
sequenceDiagram
  participant C as Creator CMS
  participant A as Core API
  participant S as S3
  participant D as MySQL + Outbox
  participant Q as SQS
  participant W as FFmpeg Worker
  participant F as CloudFront
  C->>A: Upload Session 요청
  A-->>C: S3 Multipart Presigned URLs
  C->>S: 원본 영상 파트 직접 전송
  C->>A: Complete + Checksum
  A->>S: 객체 존재/크기/체크섬 검증
  A->>D: 상태 + Outbox 원자 저장
  D->>Q: Outbox Relay (retry/idempotency)
  Q->>W: Transcode Job
  W->>S: 원본 획득 / HLS 변환 업로드
  W->>A: 결과 / 실패 이벤트
  A->>D: Renditions 및 상태 갱신
  F->>S: HLS 세그먼트 Origin Read
~~~

- 업로드 중단/중복 완료/검사 실패는 별도 상태로 처리한다.
- SQS는 at-least-once를 전제로 하며 Job ID·Digest를 통해 중복 처리해도 결과가 달라지지 않아야 한다.
- 트랜스코딩 실패 시 DLQ, 작업 상태 조회, 운영자 Retry, 불완전 HLS Prefix 정리 절차가 있어야 한다.

### 6.2 Phase 제작·스냅샷 빌드·발행

~~~mermaid
flowchart LR
  G["Git Source / Dockerfile / Compose"] --> B["Isolated Image Build"]
  B --> SCAN["SBOM / Secret / CVE Scan"]
  SCAN --> P["Phase Seed + Harness + Tests"]
  P --> TEST["Fresh Docker Compose Smoke/Integration"]
  TEST --> REG["Push OCI Digests to Registry"]
  REG --> MAN["Release Manifest + Checksums"]
  MAN --> S3["Immutable S3 Bundle"]
  S3 --> PUB["Atomic Publish Pointer"]
  PUB --> WEB["Phase Download Available"]
~~~

**빌드 게이트:** Image Build 성공 → Secret/취약점 정책 확인 → 독립 Phase 복원 → 서비스 Health Check → 검증 스크립트 → 아키텍처별 실행 확인 → Release Manifest 확정 → Atomic Publish.

- 실패한 빌드/검사는 발행을 막고 구체적 오류를 Creator에게 전달한다.
- OCI 이미지/번들은 내용 주소로 검증하고, 중복 저장 및 롤백이 가능하도록 이전 Release를 보존한다.
- 태그는 보기 좋은 별칭이며, 재현성/보안 검사·배포의 기준은 SHA-256 Digest다.
- 오픈된 사용자 Dockerfile을 빌드할 때는 공유 호스트의 고권한 Docker Socket 접근을 금지하고 별도의 격리된 Build Worker에서 수행한다.

### 6.3 학습자 Phase 다운로드·실행·검증

~~~mermaid
sequenceDiagram
  participant L as Learner
  participant P as Vue Portal / CLI
  participant A as Core API
  participant R as OCI Registry
  participant S as S3 Artifacts
  participant D as Local Docker
  participant AI as User AI Tool
  L->>P: Video / Phase / Release 선택
  P->>A: 접근 권한 + Manifest 요청
  A-->>P: Manifest + Short-lived Credentials
  P->>S: Harness/Compose/Seed/Verify Bundle 다운로드
  P->>R: Digest 고정된 Images Pull
  P->>P: Checksum/Arch/Resources 검사
  P->>D: isolated Compose Project Start
  P->>AI: Harness 경로 안내 (도구 권한은 별도)
  L->>D: 코딩·실습·테스트
  P->>D: Validation 실행
  D-->>P: Test Result + Evidence
  P-->>L: 성공/실패·힌트·다음 Phase
~~~

- S3 번들은 만료되는 URL로 내려받고 전송 중단 시 재시도한다. 이미지 Pull은 Registry 표준 인증을 사용한다.
- 기본값: 컨테이너 rootless/비특권, 리소스 제한, 제한된 네트워크, 최소 포트 노출, 필요한 마운트만 명시.
- Docker Compose 검증은 Windows/macOS/Linux의 호스트 환경 차이를 고려한다.
- 자동 검증은 사용자 환경에서 실행되므로 신뢰할 수 있는 자격증명/성적 판정이 필요하면 별도의 격리된 서버 측 평가가 필요하다.

### 6.4 실시간 상태 알림·라이브 (P1/P2)

- Build/Transcode 상태는 MySQL에 영속 기록한 뒤 WebFlux SSE로 전달한다. 재연결 시 Last-Event-ID 또는 상태 API로 누락을 보정한다.
- Redis Pub/Sub은 실시간 인스턴스 간 전파에만 사용한다. 영속 메시지는 MongoDB(채팅/이벤트) 또는 MySQL(작업 상태)에 저장한다.
- P2 라이브 영상은 OBS RTMP → Media Origin → HLS → CDN/Player. 채팅과 영상 경로는 분리한다.
- 중요한 이벤트와 생명주기 상태는 단순 Pub/Sub 도착 여부에 종속하지 않는다.

## 7. 저장소·키·버전 모델

### 7.1 S3 Bucket 경계 예시

~~~text
s3://dockvue-media/
  videos/{video_id}/original/{upload_id}
  videos/{video_id}/hls/{transcode_version}/...

s3://dockvue-registry/
  [OCI Registry managed blobs/manifests — 직접 조작 금지]

s3://dockvue-artifacts/
  videos/{video_id}/releases/{release_version}/manifest.yaml
  videos/{video_id}/releases/{release_version}/phases/{phase_id}/compose.yaml
  videos/{video_id}/releases/{release_version}/phases/{phase_id}/harness/AGENTS.md
  videos/{video_id}/releases/{release_version}/phases/{phase_id}/harness/...
  videos/{video_id}/releases/{release_version}/phases/{phase_id}/fixtures/...
  videos/{video_id}/releases/{release_version}/phases/{phase_id}/verify.sh
  videos/{video_id}/releases/{release_version}/checksums.sha256
~~~

- 환경별 계정/버킷 분리, 서버측 암호화, Public Access Block, 최소 권한 IAM, Lifecycle, 백업 정책.
- Release Prefix는 사실상 불변으로 운영하고 수정·삭제는 별도 보존 정책과 감사가 필요하다.
- 대용량 미디어/OCI 레이어는 Git에 커밋하지 않는다.

### 7.2 Release Manifest 예시 (필드 스키마 초안)

~~~yaml
schema_version: "1"
video_id: "redis-performance"
repository: "registry.dockvue.example/videos/redis-performance"
release_version: "1.0.0"
released_at: "2026-11-21T00:00:00Z"
phases:
  - id: "phase-03"
    ordinal: 3
    start_ms: 2100000
    end_ms: 3600000
    title: "Redis Cache Stampede"
    runtime: "docker-compose"
    platforms: ["linux/amd64", "linux/arm64"]
    images:
      api:
        ref: "registry.dockvue.example/videos/redis-performance@sha256:REPLACE_WITH_ACTUAL_DIGEST"
      redis:
        ref: "redis@sha256:REPLACE_WITH_ACTUAL_DIGEST"
    compose: "phases/phase-03/compose.yaml"
    fixtures:
      - "phases/phase-03/fixtures/mysql.sql.gz"
    harness:
      entrypoint: "phases/phase-03/harness/AGENTS.md"
      policy: "coach"
    validation:
      command: "./verify.sh"
      timeout_seconds: 120
    resources:
      cpu_cores: 2
      memory_mb: 2048
      disk_gb: 5
~~~

참고: 위 Digest는 설명용 Placeholder이며 실제 게시 시 64자리 SHA-256 및 각 아티팩트 체크섬으로 대체해야 한다. 제3자 이미지는 같은 논리 Repository 밖에 있더라도 고정된 Digest로 참조할 수 있다.

### 7.3 데이터베이스의 책임

- **MySQL (정합성 소스):** Users, Tenants, Memberships, Videos, Phases, Repositories, Releases, PhaseArtifacts, UploadSessions, Jobs, OutboxEvents, Progress, AuditEvents. 외래키·UNIQUE 제약, 낙관적 잠금, 격리 수준과 복합 인덱스 사용.
- **MongoDB (대량 이벤트):** build_logs_summary, realtime_events, playback_events, P2 chat_messages, 선택 동의된 학습 이벤트. 영상/Job/시간 기반 복합 인덱스, TTL 보존 기간, Batch Insert, Aggregation 사용.
- **Redis (재생성 가능한 상태):** 콘텐츠/권한 캐시(권한 캐시는 무효화 정책 필수), 세션성 presence, Rate Limit, 단기 빌드 상태 전파, Pub/Sub. TTL/Jitter/Single-flight 및 장애 시 DB Fallback 정책.
- **SQS:** TranscodeJob/BuildJob 비동기 배치 전달, Retry/DLQ. 메시지 중복·순서 불확실성을 전제로 멱등 소비자 구현.

## 8. 서비스 및 기술 스택 설계

| 구성 | 기술 | 심화 적용 |
|---|---|---|
| core-api | Java, Spring Boot MVC, JPA, MySQL | RBAC, 트랜잭션, Outbox, Idempotency, Optimistic Lock |
| realtime-api | Java, Spring WebFlux, Reactor, Reactive Mongo/Redis | SSE, WebSocket(P2), Backpressure, Non-blocking, Reconnect |
| creator-admin | TypeScript, Angular, RxJS | Reactive Forms, Validation, 동시 편집/충돌, 업로드 상태 |
| learner-web | TypeScript, Vue, Pinia, HLS.js | Video Timeline, Phase 전환, 재생 상태, 모바일 UX |
| media-worker | FFmpeg/FFprobe, SQS, S3 | HLS rendition, GOP, 재시도, DLQ, 중복 처리 |
| image-builder | Docker/BuildKit, isolated executor, OCI | Multi-arch, SBOM, Secret Scan, Fresh Restore Test |
| registry | OCI Distribution + S3 storage | Docker API, Pull Auth, Digest, Layer Deduplication |
| lab-cli (P1) | Go 권장 (단일 바이너리) | Docker/Compose 구동, 체크섬, OS/Arch 진단, 복구 |
| infra | Ubuntu, EC2, VPC, S3, CloudFront, SQS, IAM, Terraform | IaC, systemd, Nginx, 네트워크, OS 디버깅 |
| observability | Prometheus, Grafana, CloudWatch, OpenTelemetry | RED/USE 메트릭, 분산 추적, 알림, 감사 |
| android-player (P2) | Kotlin, Media3, C++/JNI | Ring Buffer, EWMA, 버퍼링 QoE, 네이티브 메모리 관리 |
| legacy-cms (P2) | AngularJS → Angular | 계약 테스트, 점진 마이그레이션, 레거시 격리 |

- WebFlux Event Loop에서 JDBC/JPA/Blocking I/O를 직접 실행하지 않는다. Core API와 Realtime API의 책임을 분리한다.
- AngularJS는 지원 종료 기술이므로 운영 메인 UI에는 채택하지 않고 마이그레이션 실험용으로만 격리한다.
- 모든 대체 라이브러리/서비스 선택은 Architecture Decision Record(ADR)에 기록한다.

## 9. API 계약 초안

### 9.1 Core REST (Spring MVC)

| Method | Path | 기능 |
|---|---|---|
| POST | /api/v1/auth/login | 로그인 |
| POST | /api/v1/tenants | Tenant 생성 |
| POST | /api/v1/videos | Video 생성 + Repo 예약 |
| GET | /api/v1/videos | 검색/공개 목록, Cursor |
| POST | /api/v1/videos/{videoId}/uploads | S3 업로드 세션 |
| POST | /api/v1/uploads/{uploadId}/complete | 업로드 검증/변환 작업 |
| GET | /api/v1/videos/{videoId}/phases | Phase 목록/타임라인 |
| PUT | /api/v1/videos/{videoId}/phases/{phaseId} | Phase 수정(버전 검증) |
| POST | /api/v1/videos/{videoId}/releases | Build/Validation 준비 |
| POST | /api/v1/releases/{releaseId}/publish | 릴리스 발행 |
| GET | /api/v1/releases/{releaseId}/manifest | 권한 확인 후 Manifest |
| POST | /api/v1/phases/{phaseId}/download-credentials | 번들 URL/Registry 권한 |
| POST | /api/v1/videos/{videoId}/playback-sessions | 영상 재생 권한 |
| POST | /api/v1/phases/{phaseId}/progress | 선택적 진도 저장 |
| GET | /api/v1/jobs/{jobId} | 작업 상태·실패 원인 |

### 9.2 실시간 API (WebFlux)

| Transport | Path | 기능 |
|---|---|---|
| SSE | /api/v1/jobs/{jobId}/events | 빌드/변환 상태 이벤트 |
| SSE | /api/v1/videos/{videoId}/events | Phase 발행 및 공지 |
| WebSocket(P2) | /ws/live/{liveId}/chat | 실시간 채팅·재연결 |

- 요청: Bearer Token 또는 보안 쿠키, request_id, tenant context. 쓰기 API는 Idempotency-Key 지원 대상 명시.
- 응답: 공통 Error Code, Trace ID, Validation Details, 401/403/404 구분(정보 노출 최소화), 409 버전 충돌, 429 제한 초과.
- 페이징: 오프셋 대신 정렬 기준에 맞는 Cursor 기반, 인덱스 계획 포함.
- API 계약은 OpenAPI로 생성하고, 클라이언트 계약 테스트에서 호환성을 검증한다.

## 10. 보안·신뢰 경계

1. **콘텐츠 공급망:** 이미지는 SBOM·시크릿 검사·취약점 정책·서명/인증·Digest 검증을 거친다. 정책 불통과 이미지에 대한 배포 차단.
2. **빌드 격리:** 제3자 Dockerfile/스크립트는 임시 격리 빌드 워커에서 실행. 운영 호스트 Docker Socket, IAM 메타데이터, 내부망, 장기 자격증명 접근 금지.
3. **학습자 보호:** 이미지·Compose·Harness가 악성일 수 있음을 표시. 기본 privileged=false, host 네트워크/PID/Socket 마운트 금지, UID/리소스/포트 제한. Docker 컨테이너는 완벽한 샌드박스가 아니므로 고위험 실습은 격리 VM을 안내.
4. **Harness Prompt Injection:** 하네스는 신뢰되지 않는 콘텐츠이며 AI 도구의 실제 권한을 확대하지 못한다. Auto 모드도 사용자 명시 동의, 위험 명령 승인이 필요.
5. **Registry/Auth:** TLS 필수, 짧은 수명의 Repo-Scoped Pull 토큰. CLI는 토큰을 stdout/로그에 남기지 않고 시스템 Credential Helper 활용.
6. **S3/CDN:** 객체 기본 비공개, 제한된 Presigned URL/CloudFront Signed Cookie, 서버측 권한 확인. URL 만료/권한 취소 정책 명시.
7. **공개/비공개 코드:** 이미지 레이어·DB Seed·Harness에 API Key/개인 데이터 포함 금지. PII 마스킹, 감사 로그 분리.
8. **검증 신뢰:** 로컬 검사 결과는 조작 가능. 사용자 인증 성적 등 고신뢰 판정은 별도 서버 격리 실행 필요.
9. **운영:** IAM Least Privilege, SSM Parameter Store, 키 교체, Egress/Ingress 제한, Rate Limit 및 예산 알림.
10. **공급망:** GitHub Actions Permissions 최소화, 고정된 Action 버전, PR의 미검증 스크립트에 프로덕션 비밀 접근 금지.

## 11. 장애·복구·정합성 정책

| 상황 | 관찰 지표 | 대응/검증 |
|---|---|---|
| 업로드 중단 | Multipart 미완료 수, 실패율 | 재개/만료/정리; 같은 완료 요청의 멱등성 |
| 영상 처리 실패 | TranscodeJob 실패, DLQ 증가 | 입력 검증, 부분 객체 정리, 안전한 재시도 |
| Build 실패 | Phase Restore/Verify 실패 | Release 발행 차단, 실패 로그/재현 명령 제공 |
| SQS 중복 | 동일 Job 재수신/중복 실행 | 상태의 원자 전이, 멱등 소비자, Visibility Extension |
| Registry 인증 만료 | 401 Pull, Pull 실패율 | Token Refresh, Retry/Resume, 관측 |
| Registry-S3 장애 | Pull 5xx, Storage Error | Backoff, 보존된 Release, 복구 테스트 |
| DB Seed 불일치 | Schema Version 불일치 | Seed/마이그레이션 고정, Clean Restore Test |
| Redis 장애 | Cache Hit 저하, Event 손실 | DB Fallback, TTL 정책, 영속 이벤트 재조회 |
| WebFlux Blocking | Event Loop 지연, p99 상승 | BlockHound/Thread Dump, Reactive Driver |
| OS 자원 고갈 | FD/CPU/디스크/Memory | systemd 복구, Alert, 로그/덤프 분석 |
| Relay 중단 | Outbox Pending 장기화 | 재시도·모니터링·전달 멱등성 |
| 발행 중 장애 | Manifest 일부만 보임 | 완성/검사 후 원자적 Current Release 전환 |

- 각 실험은 재현 조건(환경/데이터/부하), 가설, 증거, 패치, Before/After, 회귀 테스트를 docs/troubleshooting/에 기록한다.
- 장애 주입은 프로덕션 환경이 아닌 명시적 테스트 환경에서 시행한다.
- DB 백업의 실제 복원, S3 Versioning/보존, 이미지 재Pull, 서비스 재기동을 정기적으로 검증한다.

## 12. 관측·성능·품질 목표

### 12.1 메트릭

- API: 요청률, 오류율, p50/p95/p99 지연, DB Pool 대기, MySQL Slow Query, Lock Contention.
- 실시간: SSE 연결 수, Event Loop Delay, Backpressure Drop, 이벤트 전달 지연, 재연결 회수.
- 미디어: HLS 변환 시간/성공률, 첫 재생 시간, Stall Rate, 세그먼트 다운로드 실패.
- 실습: Image Pull 바이트/시간, Clean Restore 성공률, Phase Verify 성공률, Arch별 오류, Reset 실패율.
- 인프라: EC2 CPU/메모리/디스크/FD, S3/CloudFront Transfer, Registry 4xx/5xx, SQS AgeOfOldestMessage/DLQ.
- 보안/운영: 인증 거절, 위협 이벤트, 취약 이미지 비율, 비용/쿼터 초과.

### 12.2 성능/완료 목표 (측정 전 가설)

| 지표 | 초깃값(목표) | 측정 조건 |
|---|---|---|
| 핵심 API p95 | 300 ms 이하 | 정의한 개발 부하·인스턴스·데이터 규모 기록 |
| 재생 시작 p95 | 3초 이하 | 지정된 대역폭/네트워크, VOD Warm Cache 조건 |
| Phase Clean Restore | 100% 성공 | 승인된 OS/Arch·신규 볼륨·핀된 Digest |
| 빌드/변환 중복 | 사용자에게 중복 발행 없음 | Retry/DLQ/동시 호출 장애 주입 |
| 무권한 접근 | 전부 403/404/401 및 객체 다운로드 차단 | API·Registry·S3·CDN 계약 테스트 |
| 구조화된 장애 보고서 | 6건 이상 | 재현 스크립트와 검증 로그 포함 |

목표치는 실제 측정 결과가 아니다. 기준 하드웨어·테스트 데이터·부하 시나리오·비용을 함께 공개한다.

## 13. 테스트 전략 및 수용 기준

### 13.1 테스트 피라미드

- Unit: Domain/권한/Manifest Parser/Checksum/CLI 계획 생성/RxJS 스트림 로직.
- Integration: Testcontainers 기반 Spring+MySQL+MongoDB+Redis; S3/SQS 호환 테스트; OCI Registry 로컬 테스트.
- Contract: OpenAPI 스키마, 이벤트 버전, Manifest Schema, CLI ↔ Core API 호환.
- E2E: 영상 업로드 → HLS 재생 → Phase 선택 → Manifest/Images Pull → Clean Compose Start → Verify.
- Resilience: Worker 강제 종료, 네트워크 지연/Disconnect, Registry Token Expire, Seed 훼손, 디스크 부족.
- Security: Tenant 탈출, 잘못된 권한/서명 URL, 이미지 취약점/비밀정보, Prompt Injection 가이드 준수.
- Load: k6 기반 API/SSE, 이미지 Pull 병렬, 미디어 CDN 부하; 테스트 전후 Prometheus/Grafana 증거 보관.

### 13.2 P0 최종 수용 테스트

1. Creator가 채널/영상을 만들고 S3로 직접 업로드하면 HLS가 생성되어 권한에 맞게 재생된다.
2. 하나의 영상에 서로 다른 시작 시각을 가진 3개 이상 Phase를 설정하고, 각 Phase가 별도의 환경/하네스를 참조한다.
3. 최소 1개 Phase는 Spring Boot + MySQL + Redis 등 다중 컨테이너이며, 클린 머신에서 재현 가능하다.
4. 사용자는 이전 Phase를 수행하지 않고 Phase 3 환경을 가져와 실행할 수 있다.
5. 동일 Manifest/Digest/Seed로 재설치했을 때 같은 검사 결과를 얻는다(비결정적 테스트는 제거/고정).
6. AI Harness와 검증 스크립트가 제공되고, 권한을 승인한 AI 도구 또는 학습자가 활용할 수 있다.
7. verify 실패 시 이해 가능한 오류·힌트·복구 방법을 보여준다.
8. 다른 테넌트의 비공개 영상·Harness·Registry 리소스를 다운로드하지 못한다.
9. Worker 장애/중복 SQS/이미지 불일치에서 데이터 손상·중복 릴리스 없이 복구한다.
10. 공개 README에 구현 상태를 정확히 표시하고, 검증 가능한 데모/실행 문서/기술 보고서를 제공한다.

## 14. 프로젝트 구성 (권장 Monorepo)

~~~text
DocVue/
├── README.md
├── .gitignore
├── docs/
│   ├── Function_Spicification.md
│   ├── architecture/          # C4, network, ADR
│   ├── api/                   # OpenAPI / schema
│   ├── runbooks/              # deploy/backup/recovery
│   ├── benchmarks/            # before-after performance
│   └── troubleshooting/       # reproducible incidents
├── apps/
│   ├── creator-admin/         # Angular/TypeScript
│   ├── learner-web/           # Vue/TypeScript
│   ├── android-player/        # P2 Kotlin + NDK
│   └── legacy-angularjs/      # P2 migration lab
├── services/
│   ├── core-api/              # Spring MVC + JPA
│   ├── realtime-api/          # Spring WebFlux
│   ├── media-worker/          # FFmpeg
│   ├── image-builder/         # OCI Build/Validation
│   └── registry-auth/         # Scoped token
├── tools/
│   └── dockvue-cli/           # P1 Docker/Compose CLI
├── packages/
│   ├── api-contracts/
│   └── manifest-schema/
├── infra/
│   ├── terraform/
│   ├── docker/
│   └── observability/
├── tests/
│   ├── integration/
│   ├── e2e/
│   ├── load/
│   ├── resilience/
│   └── fixtures/
└── .github/
    └── workflows/
~~~

상기 구조는 **목표 디렉터리**로, 생성되지 않은 폴더/서비스를 이미 구현된 것으로 간주하지 않는다.

## 15. CI/CD 및 인프라 운영

### 15.1 GitHub Actions CI

- PR: Markdown/Schema lint → Java Test/Static Analysis → Angular/Vue Lint/Unit → Docker Build → IaC Validate/Security Scan.
- Main 병합: 신뢰된 빌드 결과의 이미지·아티팩트 서명/게시. GitHub OIDC로 AWS 권한 수명 단축.
- Release Job: Clean Phase Restore + Verify 통과 후에만 Immutable Release Manifest 발행.
- 프런트엔드·API 배포 시 Blue/Green 또는 Rolling 중 규모에 적합한 방식을 선택, Health Check와 Rollback 경로 보장.
- 이미지 스캔 정책, 라이선스/SBOM, API 호환성 검사, Harness-Image 버전 검사 실패 시 차단.

### 15.2 AWS / Ubuntu

- VPC Public Edge·Private Workload/DB 분리, ALB/Reverse Proxy 및 TLS, EC2 Ubuntu 또는 필요 시 관리형 서비스.
- CloudFront+S3로 VOD, 별도 OCI Registry+S3로 레이어, S3 Artifacts로 하네스·Seed.
- SQS+DLQ 작업 큐, CloudWatch 알림, Prometheus/Grafana, SSM Parameter Store, Terraform IaC.
- 초기 비용 최적화: 소규모 EC2/로컬 Worker, 테스트 리소스 자동 정리, S3 Lifecycle 및 AWS Budgets, CDN 전송량 제한.
- 운영 Runbook: 백업·복원, 재배포/롤백, 토큰 회수, Registry Storage/GC, Worker/DB 장애, 디스크·FD·네트워크 조사.

## 16. 개발 로드맵 (1인 개발 가정, 20주 예시)

| 기간 | 목표 | 게이트 |
|---|---|---|
| 1–2주 | 도메인/DB/API/Manifest Schema, Docker Compose 기반 로컬 스택 | 스키마·기본 API 테스트 |
| 3–5주 | Spring Core, Angular Creator, Vue Learner, Auth/RBAC/Phase CRUD | 영상/Phase UI와 권한 |
| 6–8주 | S3 Multipart, FFmpeg→HLS, 첫 Phase Bundle, Docker 실행 | **사용 가능한 P0 VOD+Phase MVP** |
| 9–12주 | S3-backed OCI Registry, Image Builder, Clean Restore CI, WebFlux SSE | P1 Image Pull/상태/재현성 |
| 13–16주 | CLI, Harness 모드, 자동 검증, DB/캐시/성능 최적화 | CLI End-to-End 시나리오 |
| 17–20주 | AWS 보안·관측·장애 주입, 문서/데모 및 보고서 | 공개 데모와 6개 이상 기술 보고서 |
| P2 별도 | Live, Android NDK, AngularJS Migration, Timeline Sync | 독립 ADR 및 일정 |

기간은 추정치이고 실제 성과가 아니다. 8주차 P0가 완료되기 전 P2를 진행하지 않는다.

## 17. 대표 데모 콘텐츠

**Spring Boot + MySQL + Redis: 캐시 성능 병목과 장애 복구**

| Phase | 영상 구간(예시) | 배포 시작 상태 | 완료/검증 |
|---|---|---|---|
| 1 | 00–15분 | Spring/MySQL 기본 API | 기본 테스트 통과 |
| 2 | 15–35분 | 비효율 조회·부하 데이터 | Slow Query/EXPLAIN 재현 |
| 3 | 35–60분 | Redis 캐시 적용, Stampede 방어 없음 | 동시 요청 테스트/캐시 개선 |
| 4 | 60–90분 | 장애 주입 가능한 구성 | 재시도/정합성·복구 회귀 |

- 각 Phase: Git Revision + OCI Digests + Compose + Seed + Harness + Verify + Release Manifest.
- 예시 성과: 동시 요청 증가로 DB Pool 고갈 → 조사(메트릭/로그/쿼리) → 캐시 무효화/Single-flight·TTL Jitter 개선 → 동일 부하로 Before/After 측정.
- 장애는 프로젝트의 테스트 케이스로 재현하지만, 실제 해결 성과는 측정한 값만 기술한다.

## 18. 문서 관리·변경 절차

- 핵심 요구사항에 ID(DV-...)를 부여하고 Issue/PR/테스트와 연결한다.
- API/Manifest/권한/저장소 스키마 변경은 ADR·마이그레이션·호환성 테스트를 함께 제출한다.
- 제품 릴리스와 학습 콘텐츠 릴리스는 분리한다(Platform Version vs Video Release Version).
- 우선순위·범위가 바뀌면 본 명세를 먼저 갱신한다.
- 기존 저장소 LICENSE의 AGPL-3.0 조항을 검토하고 프로젝트 코드·배포 의무를 준수한다.
