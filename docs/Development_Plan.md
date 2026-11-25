# DockVue 개발 계획 — Phase 00–15

기준: 2026-11-26 / 상태: Phase 00 설계 승인 및 종료, Phase 01 착수 가능(미착수). 제품 코드 미작성.
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
- [ ] Phase 01: 정확한 버전 고정, 빌드 및 정식 OpenAPI/JSON Schema 검증
- [ ] Phase 08: 지원 OS/아키텍처 실기기 검증
- [ ] Phase 11: AWS 비용/배치 승인 및 실제 복구 검증

설계 게이트 통과. Phase 01~15 미착수.

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
