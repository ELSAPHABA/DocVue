# DockVue

**Interactive video learning with phase-based Docker environments and AI harnesses.**

영상의 학습 구간(Phase)마다 실행 가능한 컨테이너 환경과 AI 실습 지침을 연결하는 개발자 교육 플랫폼입니다. 시청자는 원하는 구간의 Docker 환경을 로컬에 복원하고, 자신의 AI 도구로 실습하며 검증 결과를 확인할 수 있습니다.

> **개발 상태: Phase 04 VOD 파이프라인 구현·로컬 검증 완료** — 인증·RBAC, CMS, Phase 타임라인, 공개 미리보기, S3 multipart 업로드 재개, SQS/FFmpeg HLS, 영상 탐색과 이어보기가 동작합니다. 실제 AWS CDN 및 실습 패키지/Release는 후속 구현 목표입니다. [개발 계획](docs/Development_Plan.md) · [확정 기술 계약](docs/Technical_Contracts.md)

## 로컬 개발

Java 21, Node.js 24, Python 3.10+ 및 Docker Compose 필요. Windows에서는 Conda 환경을 활성화한 후 실행할 수 있습니다.

~~~powershell
conda activate HongDev
if (!(Test-Path .env)) { Copy-Item .env.example .env }
docker compose --profile core --profile media up -d --build --wait
npm ci
python scripts/smoke.py
python scripts/smoke-frontends.py
~~~

`npm run dev:admin` / `npm run dev:learner`는 별도 터미널에서 실행합니다. Windows 포트 예약과 충돌하면 `.env`의 호스트 포트를 조정합니다. 로컬 인증·초대 메일은 Mailpit에서 확인합니다(실제 외부 발송 없음). 이메일 인증과 플랫폼 제작자 승인 후 CMS에서 채널·영상·Phase를 편집하고 영상을 업로드합니다. 변환 완료 및 미리보기 활성화 후 학습자 포털에서 HLS를 재생할 수 있습니다. LocalStack은 개발 전용이며 영구 보관을 보장하지 않습니다. [실행·검증](docs/architecture/Phase_01_Bootstrap.md) · [인증](docs/architecture/Phase_02_Identity.md) · [콘텐츠](docs/architecture/Phase_03_Content.md) · [VOD 및 headless E2E](docs/architecture/Phase_04_VOD.md) · [트러블슈팅](docs/troubleshooting/).

## 핵심 기능

- **Phase 기반 학습:** 영상 타임라인을 Phase로 분할하고 구간별 실습 진입점 제공
- **재현 가능한 실습:** 영상당 하나의 논리적 OCI Repository, Phase별 이미지 Digest·Docker Compose·DB Seed 고정
- **AI Harness:** Phase마다 AGENTS.md 등 실행 문맥·목표·힌트·검증 기준 제공
- **로컬 실행·검증:** Docker 환경 다운로드, 실행·초기화·자동 검사(전용 CLI는 후속 단계)
- **콘텐츠 제작·운영:** 관리자 CMS, VOD/HLS 배포, 빌드/발행 파이프라인, 권한 및 장애 모니터링

## 아키텍처

~~~mermaid
flowchart LR
  C["Angular Creator CMS"] --> API["Spring Core API"]
  V["Vue Learner + HLS Player"] --> API
  API --> DB[("MySQL / Redis")]
  API --> Q["SQS"]
  Q --> B["Build + FFmpeg Workers"]
  B --> S3[("S3 Artifacts / VOD")]
  B --> R["OCI Registry (S3 backend)"]
  S3 --> CDN["CloudFront"]
  CDN --> V
  L["Local Docker / DockVue CLI"] --> R
  L --> S3
  L --> H["Phase Harness + Verify"]
~~~

**주요 스택:** Java/Spring MVC·WebFlux, TypeScript/Angular·Vue, MySQL·MongoDB·Redis, Docker/OCI·FFmpeg, AWS(S3·CloudFront·SQS·EC2), Ubuntu·Terraform. Android NDK, 라이브 스트리밍, AngularJS 마이그레이션은 확장 범위입니다.

## 실행 흐름

1. 제작자가 영상을 업로드하고 Phase별 Docker/Compose·Seed·AI Harness·검증 규칙을 등록합니다.
2. 빌드 파이프라인이 이미지를 생성하고 재현성을 검사한 뒤 OCI Registry/S3에 **불변 Release**를 게시합니다.
3. 학습자가 영상에서 Phase를 선택해 필요한 환경과 하네스를 내려받고 로컬 Docker에서 실행합니다.
4. AI의 도움으로 실습하고 검증 스크립트로 결과를 확인합니다.

첫 데모 콘텐츠는 **Spring Boot + MySQL + Redis의 성능 병목 및 Cache Stampede 해결 실습**을 목표로 합니다.

## 개발 계획

| 단계 | 범위 |
|---|---|
| P0 | VOD/HLS, Phase 편집, S3 번들, Docker 실행, Harness·Verify |
| P1 | S3-backed OCI Registry, 전용 CLI, 빌드 자동화, 실시간 상태, 운영 |
| P2 | 라이브·채팅, Android NDK 재생 분석, 레거시 CMS 전환, 영상-텔레메트리 동기화 |

상세 요구사항, 서비스 구조, 영상/이미지 빌드·배포 워크플로, API, 보안 및 테스트 계획은 [기능명세서](docs/Function_Spicification.md)를 참고하세요.

## 라이선스

[GNU Affero General Public License v3.0](LICENSE) — 저장소에 포함된 LICENSE 기준.
