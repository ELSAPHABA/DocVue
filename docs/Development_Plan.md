# DockVue 개발 계획 — Phase 00–15

기준: 2026-11-26 / 상태: Phase 00 승인, Phase 01–03 구현·CI 검증 완료, Phase 04 구현 및 Docker 내부 headless E2E 검증 완료. Phase 05 미착수.
상위 제품 요구사항: [기능명세서](Function_Spicification.md). 세부 계약: [Technical Contracts](Technical_Contracts.md).
여기서 개발 Phase는 영상 내 학습 Phase와 구분한다. 기능 우선순위 P0/P1/P2와도 별개이다.

| 개발 Phase | 구현 범위 | 선행 | 수용 게이트 / 증거 |
|---|---|---|---|
| 00 | 버전 정책, ADR, ERD, API, 이벤트, Manifest, 보안 | 없음 | 설계 산출물 작성 및 D00-01~08 사용자 승인 완료; 런타임/정식 스키마 검증 미수행 |
| 01 | Gradle 멀티 프로젝트, Angular/Vue workspace, Compose, CI, DB 마이그레이션 골격 | 00 | clean clone → 빌드, health, 테스트; lockfile·digest 고정 |
| 02 | 인증, refresh rotation, tenant/RBAC, 초대, 감사 | 01 | 권한 매트릭스, 타 테넌트 IDOR/토큰 재사용 테스트 |
| 03 | CMS, 영상·Phase 편집, Vue 검색/진도 UI | 02 | 동시 편집 409/412, 시간 범위, cursor, UI E2E |
| 04 | S3 multipart, outbox/SQS, FFmpeg HLS, 재생 | 03 | 업로드 재개, 중복 Job, HLS 재생, private CDN |
| 05 | Phase bundle, source workspace, fixture, harness, local verify | 03,04 | 세 Phase, clean restore, TAR fallback, 권한/해시; MVP |
| 06 | S3-backed Distribution, repo token/auth, staging/promote | 05 | 표준 pull, scope 격리, token expiry, GC dry run |
| 07 | 격리 BuildKit, source pin, SBOM/scan/sign, publish | 06 | 깨끗한 restore, fencing, 실패 발행 차단, rollback |
| 08 | Go CLI, download/start/status/verify/export/reset/stop | 07 | Windows/macOS/Linux 대상 smoke, 취소/충돌/복구 |
| 09 | Harness coach/pair/auto, AI adapter 안내, 진도 | 08 | 문서-환경 버전 일치, 권한 경계, 동의된 검증 결과; 핵심 제품 |
| 10 | WebFlux SSE, reactive Mongo/Redis, replay/backpressure | 09 | 재연결·순서·느린 소비자, blocking 탐지, 부하 증거 |
| 11 | AWS Ubuntu, Terraform, TLS, DB backup, observability | 10 | 외부 공개, IaC 재현, 복원, 보안/비용 게이트 |
| 12 | OBS/RTMP/SRS/HLS, chat, Phase live activation | 11 | 영상·Phase 시간 매핑, 재연결, 채팅 복구 |
| 13 | Android Media3, C++ JNI QoE, telemetry | 11 | native/Kotlin 동일 결과, 프로파일·실기기 검증 |
| 14 | 격리 AngularJS CMS → Angular 점진 전환 | 03,11 | 기능 동등성, hybrid 단계, 최종 의존성 제거 |
| 15 | 전체 E2E, 성능/장애·보안, 문서/데모 | 12,13,14 | 실제 측정 보고서, 릴리스/운영 승인 |

## Phase 00 종료 기록

- [x] 기능명세 분석, 개발 로드맵 및 기술 계약 작성
- [x] D00-01~08 권장안 사용자 승인 및 문서 반영 ([확정 계약](Technical_Contracts.md))
- [x] Phase 01: 핵심 의존성 및 이미지 고정, 빌드 및 정식 OpenAPI/JSON Schema 검증
- [ ] Phase 08: 지원 OS/아키텍처 실기기 검증
- [ ] Phase 11: AWS 비용/배치 승인 및 실제 복구 검증

설계 게이트 통과.

## Phase 01 종료 기록

- [x] Monorepo, Spring MVC/WebFlux, Angular/Vue, Compose 및 LocalStack 초기 구현
- [x] Gradle Wrapper/Lockfile, npm Lockfile, OCI base image digest 적용
- [x] 초기 Linux CI 4개 Job 통과, Windows HongDev Java 및 프런트엔드 빌드/테스트와 계약 검증
- [x] 로컬 Docker 통합 smoke, 개발 서버 API proxy 및 포트 충돌 해결
- [x] 재현 절차와 실제 트러블슈팅 기록: [Bootstrap](architecture/Phase_01_Bootstrap.md), [TS-01](troubleshooting/TS-01-windows-port-exclusion.md)

Phase 01 로컬 게이트 및 GitHub Actions 4/4 통과. Phase 02 로컬 게이트와 [GitHub Actions 4/4](https://github.com/ELSAPHABA/DocVue/actions/runs/45762333038) 통과. [Phase 02 검증 및 제한](architecture/Phase_02_Identity.md).

## Phase 03 종료 기록

- [x] V3 migration, JPA Video @Version, 논리 repo 예약, Phase 타임라인, scope-bound cursor와 metadata preview
- [x] Angular CMS / Vue learner portal, 공통 디자인 시스템, 검색 및 학습 시작 위치 저장
- [x] Java/npm 및 OpenAPI 검증, Docker 내부 Playwright Chromium headless 종합 시나리오 통과
- [x] 실제 MySQL EXPLAIN, 1440px/390px 화면 캡처, Windows 연결 reset 장애 기록
- [x] [콘텐츠 설계](architecture/Phase_03_Content.md), [ADR-0007](adr/0007-content-preview-and-concurrency.md), [TS-03](troubleshooting/TS-03-content-query-and-browser-transport.md)

Phase 03 로컬 기능 검증 및 [GitHub Actions 4/4](https://github.com/ELSAPHABA/DocVue/actions/runs/45765529884) 완료; Windows 호스트 전체 E2E는 연결 reset으로 실패했으므로 Docker 내부 검증과 구별한다.

## Phase 04 종료 기록

- [x] V4 migration, S3 multipart 세션 및 재개, 파트/전체 해시 검증, job/outbox, fencing/lease
- [x] FFmpeg 7.1.5 격리 Worker, HLS, CMS 업로드 UI 및 학습자 재생/탐색/이어보기
- [x] Docker 내부 Chromium headless 실제 17 MiB multipart 및 360/720p 재생 E2E, 콘텐츠 회귀, Worker 12개 테스트, 실제 SQS 완료 메시지 재전송
- [x] [VOD 구현 및 검증](architecture/Phase_04_VOD.md), [ADR-0008](adr/0008-vod-outbox-and-fencing.md), [TS-04](troubleshooting/TS-04-vod-duplicate-delivery.md)
- [ ] 실제 AWS S3/IAM/OAC/CloudFront private CDN (Phase 11 승인/비용/보안 게이트), Release 고정 재생(Phase 05–07)

Phase 04 로컬 게이트 완료. 최종 CI는 해당 커밋 Actions에서 확인한다. Phase 05–15 미착수. Windows 호스트 E2E 전체는 이번 단계에서 재시도하지 않았으며 Docker 내부에서 headless 검증했다.

## 개발 운영 규칙
- AI-DLC: 요구사항 → 설계/대안 → 사용자 검토 → 구현 → 테스트 → 문제 분석 → 문서 → Gate. 명시된 변경만 구현한다.
- 기능 명세 ID(DV-...)를 PR/테스트에 연결한다. 각 Phase 시작 전 범위·기준 HEAD·검증 명령·비목표 기록. 다음 Phase 자동 진행 금지.
- 제품 코드, 인프라, 실습 콘텐츠의 버전은 독립적으로 관리한다. 계약 변경은 ADR과 호환성 테스트 포함.
- 실제 장애와 의도적 fault injection 구분. 발생하지 않은 장애나 성능 개선을 성과로 기재하지 않는다.
- Gate report: 변경 파일, 실제 테스트 명령·종료 코드, 미검증 환경, 재현 조건, 위험, 다음 작업. 실패/skip은 PASS로 계산하지 않는다.

## 트러블슈팅 백로그
| ID | 후보 | 검증 |
|---|---|---|
| TS01–04 | Compose health/DNS, refresh race, SQL N+1, RxJS race | logs, execution plan, concurrency test |
| TS05–07 | SQS duplicate, HLS stall, fixture/volume mismatch | job fence, QoE, clean restore |
| TS08–11 | Registry expiry/transfer, layer reuse, interrupted publish, multiarch | scope test, bytes, fault injection, architecture matrix |
| TS12–13 | CLI port/path, Harness mismatch | cross-platform smoke, manifest rejection |
| TS14–17 | blocking, backpressure, Redis stampede, Mongo ingestion | profiling, p95/p99, query plan |
| TS18–21 | Ubuntu resource pressure, live drift, JNI, legacy regression | sys metrics, time alignment, native profiling, E2E |

최소 6건의 깊이 있는 보고서를 목표로 한다. 16–22주라는 이전 계획은 추정이며 인력/비용/테스트 환경에 따라 재산정한다.
