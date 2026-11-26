# Phase 03 — 콘텐츠 CMS와 학습자 포털

기준 HEAD: 68a51964d703aa6296c79c39e4bc31d881a4467e. Phase 02 CI 4/4 통과를 확인한 후 착수. 사용자 지시: Playwright는 브라우저 창을 표시하지 않는 headless 모드만 사용한다.

## 범위

DV-AUTH-03/04, DV-PHS-01/02/03, DV-OCI-01, DV-LRN-01 및 DV-LRN-02의 초기 학습 시작 위치에 대응한다. 실제 영상 파일·재생·업로드·트랜스코딩(Phase 04), 불변 Release/실습 다운로드(05–07), 검증 진도(09)는 미구현이다.

메타데이터 미리보기는 별도 opt-in 기능으로 정의한다. DRAFT 영상에 preview_enabled를 설정할 수 있지만 이는 공개 영상 발행이 아니며, 재생·실습 파일·Release ID를 제공하지 않는다. Phase가 없으면 공개할 수 없고 모두 삭제하면 미리보기가 자동으로 꺼진다. PUBLIC은 검색 노출 및 링크, UNLISTED는 링크만, PRIVATE는 활성 테넌트 구성원만 링크로 열람한다. 비활성 테넌트/보관 영상은 미노출. 공개되는 설명·Phase 정보에 비밀/답안을 입력하지 않도록 제작자가 확인해야 한다.

## 백엔드 구조

~~~mermaid
flowchart LR
  A[Angular Studio] --> S[Spring Security]
  V[Vue Learn] --> S
  S --> C[Content Controller]
  C --> P[Content Service]
  P --> T[Tenant policy]
  P --> J[JPA Video aggregate]
  P --> Q[Bounded JDBC read model]
  J --> M[(MySQL)]
  Q --> M
  T --> M
  P --> H[HMAC cursor]
~~~

- V3 Flyway: videos, video_repositories, video_labels, phases, preview_progress. 영상 등록 트랜잭션에서 논리 repo 이름 videos/{tenantId}/{videoId} 예약, 실제 Registry 생성·접근은 하지 않는다.
- 생성/변경: approved verified creator-or-admin membership 필수. 일반 Creator는 본인이 등록한 영상만, Tenant Admin은 해당 테넌트 영상 전체 관리(제작자 승인도 필요). Platform Admin에게 암묵적인 권한 없음. 서비스 재사용 권한 정책과 별개로 조회 SQL에 tenant/owner 제약을 적용하고 일치하지 않으면 404.
- Video JPA entity @Version; 쓰기 잠금 순서 tenant → actor → video. 콘텐츠 생성은 tenant/actor lock + Idempotency-Key(24h, canonical fingerprint). 영상·Phase·미리보기 변경은 같은 video version의 If-Match를 사용한다. 타임라인 전량 교체를 한 트랜잭션으로 처리(기존 ID 유지, 누락 Phase 제거, 신규 ID 발급). ordinal 임시 offset +20 후 최종 1..N으로 재배열하여 UNIQUE 충돌 방지. 모든 Phase 시간은 밀리초, [start,end), 비중첩·시작 오름차순, 최대 20개, 미디어 추정 길이 4시간 이하, 설정된 길이보다 Phase 끝이 뒤면 거부. 빈 타임라인 허용(미리보기는 숨김).
- archive는 삭제 API로 표시하되 영상 자체/논리 repo는 보존한다. 미래 Release/GC와 독립적인 draft 상태다.
- 목록은 명시적 read projection + batched labels 1쿼리, Phase 수는 같은 SQL의 correlated count. 페이지당 최대 100, 네트워크 round-trip은 결과 행 수에 비례하지 않는다. 공개 검색은 제목/설명 literal substring(LOCATE, SQL wildcard/문자열 연결 사용 안 함), tag/stack exact lowercase, difficulty 필터. 정규화 label 별도 테이블과 복합 인덱스. 대규모 본문 검색 최적화는 실측 후 결정.
- Cursor는 (catalog_at,id) DESC 공개 목록 또는 (updated_at,id) DESC 관리 목록, HMAC-SHA256 서명 및 filter/tenant/actor scope 결합. 서버 재시작 시 개발용 ephemeral key 변경으로 cursor 만료; 운영 CATALOG_CURSOR_KEY(Base64 32 bytes 이상) 공유/Secret 관리 필요. 다른 scope/tamper는 422. 페이지 간 변경에 따른 중복·누락을 완전 보장하는 snapshot은 아님.
- preview_progress는 학습자가 선택한 Phase와 당시 video_version만 저장한다. 실제 재생 위치나 완료/검증 점수가 아니다. 타인 진도 조회 불가, 다른 영상 Phase 거부, 버전 변경 시 stale 표시 및 재선택. 영상의 모든 변경은 보수적으로 이전 선택을 만료시킨다.

## API 계약

OpenAPI v0.3, /api/v1. 이전 개별 Phase 생성/수정 설계 경로는 하나의 aggregate timeline PUT으로 대체했다(실제 미구현 경로였음). 아직 구현하지 않은 media/release/job 계약은 유지한다.

| Method | Endpoint | 목적 |
|---|---|---|
| POST | /videos | idempotent draft + logical repo |
| GET | /tenants/{tenantId}/videos | 관리자/자기 영상 cursor 목록 |
| GET/PUT/DELETE | /tenants/{tenantId}/videos/{videoId} | 관리 상세, metadata 변경, archive |
| PUT | /tenants/{tenantId}/videos/{videoId}/timeline | 전체 Phase 편집 |
| PUT | /tenants/{tenantId}/videos/{videoId}/preview | 미리보기 on/off |
| GET | /videos | guest 공개 미리보기 검색 |
| GET | /videos/{videoId}, /videos/{videoId}/phases | 접근 정책 적용 상세 |
| GET/PUT | /videos/{videoId}/preview-progress | 본인 시작 위치 |

If-Match 필수 변경: 누락 428, stale 412, 상태/멱등성 충돌 409. 서버 오류는 RFC 9457, UI는 오래된 편집 값을 자동 덮어쓰지 않는다. 공개 범위는 메타데이터 미리보기에만 적용. Phase 04에서는 실제 media duration에 대해 재검증해야 한다.

## UI

공통 CSS(theme.css)로 색·간격·타이포그래피·form·card·responsive layout을 공유한다. Angular의 Reactive Forms로 metadata 검증, RxJS switchMap으로 이전 목록 응답 무효화, 저장 중 중복 클릭 방지, Phase 타임라인 및 수동 reload. Vue는 실시간 debounce 검색 및 AbortController로 경쟁 응답 방지, 상세 URL(/videos/{id}), browser back, 공개 범위/미리보기 상태, 시작 위치 저장/복원. 실제 영상·다운로드 버튼을 가짜로 제공하지 않는다. AuthClient singleton은 메모리 bearer와 기존 CSRF/refresh 계약 유지.

## 검증 절차

Windows HongDev:
~~~powershell
conda activate HongDev
npm ci
npx playwright install chromium
npm run build
npm test
./gradlew.bat --no-daemon check
python scripts/validate-contracts.py
~~~

E2E는 격리 Compose 프로젝트 dockvue-phase03-test(또는 GitHub CI 임시 stack)에서만 test DB fixture가 허용된다. 일반 개발 DB에 SQL 승인 fixture를 실행하지 않는다. CORE_HOST_PORT, REALTIME_HOST_PORT, LOCALSTACK_HOST_PORT, MAILPIT_HOST_PORT, ADMIN_HOST_PORT, LEARNER_HOST_PORT를 별도 포트로 설정하고 DOCKVUE_TEST_ALLOW_DB_FIXTURES=1 지정 후 npx playwright test 실행. Playwright config는 headless:true, chromium, workers:1, reporter open:never. 창/프로필 직접 열지 않음. 실패 trace와 데스크톱/모바일 스크린샷은 test-results/ (gitignore), CI artifact는 7일 보존. 실제 로그인/메일·채널·영상 등록과 시간 검증·검색·시작 위치·권한 검사를 수행한다.

### Windows host 연결 장애 및 대체 테스트 경로

첫 Windows host Playwright 실행에서 로그인 요청의 DB audit는 LOGIN 성공이었지만 Chromium trace는 ERR_CONNECTION_RESET으로 응답 수신 실패를 기록했다. 컨테이너는 healthy였다. 원인은 확정하지 못했으며 인증/변경 요청을 자동 재시도하지 않았다. 기존 Phase 02와 동일한 유형의 증상이다.

대체 경로로 infra/docker/playwright.Dockerfile을 작성했다. Playwright v1.58.2-noble OCI digest 및 npm lockfile 고정, Node 24.13.0, Docker 내부 Core/Mailpit DNS, mysql2 테스트 fixture, headless Chromium 사용. 테스트 컨테이너는 별도 Compose 네트워크에 연결하며 Docker socket을 마운트하지 않고 포트를 외부 공개하지 않는다. Windows 설치는 HongDev를 유지했다. CI에서는 기존 Linux host 경로로 검증한다.

### 실제 검증 기록

| 검증 | 결과 |
|---|---|
| OpenAPI 3.1 / JSON Schema | PASS |
| Gradle Java check | PASS (Core 8, Realtime 1) |
| Angular / Vue production build | PASS |
| Vitest | PASS (Angular 1, Vue 4) |
| Docker Core build / 신규 Flyway V1–V3 | PASS, 7개 서비스 healthy |
| Windows host Playwright headless | FAIL, ERR_CONNECTION_RESET |
| Docker 내부 Playwright headless | PASS, 1종합 시나리오, 12.3s test / 33.2s 전체 |
| Desktop/mobile layout | PASS, 1440px/390px capture 4개; 390px 수평 overflow 없음 |
| Catalog 기본 실행 계획 | ix_video_catalog range, tenant PRIMARY eq_ref (소규모 테스트 데이터, 성능 수치 아님) |
| Phase 02 인증/인가 및 Redis 장애 회귀 | PASS, 내부 HTTP 11개 그룹 + Redis stop/start 503 |
| 기존 개발 DB V2→V3 / Phase 01 smoke | PASS, V1/V2/V3 success=1, 11개 smoke + 두 frontend proxy |

E2E는 실제 UI로 가입·이메일 인증·로그인·채널 생성·영상 등록·Phase 시간 중첩 거절 및 저장·미리보기 전환·학습자 검색 및 상세·시작 위치 저장/복구를 검증했다. 실제 API로 누락 ETag 428, stale 412, 기간 축소/겹침 422, 중복 생성 키 201 동일 ID 및 payload 충돌 409, cursor 변조/필터 변경 422, unlisted/private 접근 정책, 타 테넌트 및 타 영상 Phase ID 거절, archive 후 접근 차단을 검증했다. 테스트용 제작자 승인만 격리 DB fixture로 처리. 화면 캡처와 trace는 test-results/ 및 playwright-report/ 아래에 저장하고 커밋하지 않는다. 실제 재생·실습은 후속 Phase이며 부하·보안 전수 검증은 수행하지 않았다.

개발 stack은 테스트 후 기존 DB volume을 유지해 재기동하고, 개발 프론트엔드는 사용자가 확인할 수 있도록 별도 프로세스로 실행한다. Windows host 전체 E2E의 성공을 주장하지 않는다.
