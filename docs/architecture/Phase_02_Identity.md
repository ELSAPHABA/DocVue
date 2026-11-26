# Phase 02 — 인증·인가 및 멀티테넌시

기준: 2026-11-26. Phase 00 D00-01~08 승인 계약 및 Phase 01 개발 기반을 유지한다. 구현/검증 대상은 Core API의 인증·테넌트 권한과 Angular/Vue 초기 인증 UI이며, 영상 CRUD/Registry 권한 발급/실제 AWS/CLI는 후속 단계다.

## 데이터 모델 및 책임

Flyway V2는 기존 V1을 수정하지 않고 users, tenants, memberships, email_verifications, refresh_families, refresh_tokens, invitations, idempotency_keys, audit_events를 추가한다. MySQL InnoDB, ASCII UUID/hash, UTC timestamp, FK·UNIQUE 및 조회 인덱스를 사용한다. Core API만 업무 DB를 변경한다. 인증 트랜잭션은 명시적 행 잠금·조건 UPDATE를 이해하기 쉽게 Spring JdbcTemplate으로 구현했다. 기존 JPA 의존성은 유지하고 Phase 03 콘텐츠 aggregate에서 JPA mapping/optimistic locking을 적용한다.

~~~mermaid
flowchart LR
  U[Angular / Vue] -->|same-origin + CSRF| S[Spring Security]
  U -->|Bearer RS256| S
  S --> R[(Redis Lua rate limit)]
  S --> I[Identity service]
  S --> T[Tenant policy]
  I --> M[(MySQL identity)]
  T --> M
  I --> E[SMTP after commit]
  T --> E
  E --> P[Mailpit local only]
~~~

## 회원가입 및 인증

- ASCII 이메일 strip/lowercase unique, 12–128자 비밀번호, Argon2id (salt 16 bytes, hash 32 bytes, parallelism 1, memory 32 MiB, iterations 3). 등록 중복은 generic 202, 기존 비밀번호를 변경하지 않는다. 알 수 없는 계정의 로그인에도 dummy Argon2 해시 확인.
- 이메일 인증 코드: 256-bit random base64url, SHA-256 해시 저장, 30분, 1회 사용. 가입/재전송 요청 후 SMTP는 DB commit 이후 실행; 메일 전송 실패 시 민감 정보 없이 경고 및 재전송 안내. 코드 URL/로그 미노출. 인증은 해당 계정으로 로그인한 상태의 bearer + CSRF + 코드가 필요하다. 이메일 확인 전 채널 생성/초대 수락/제작자 승인 불가.
- JWT RS256, issuer=dockvue, audience=dockvue-api, subject UUID, jti, iat, exp=600s. Bearer token은 브라우저 메모리만 사용. JWKS는 공개 키만 제공. DB의 사용자/멤버십/승인 상태로 실제 권한을 확인하며 JWT에 역할을 넣지 않는다.
- Refresh Token은 opaque 256-bit random, SHA-256 hash only. 30일 절대 만료(회전 시 연장 없음). HttpOnly SameSite=Lax cookie /api/v1/auth. Secure는 운영 필수, 로컬 Compose HTTP에서만 false. 인증 POST는 정확한 Origin allowlist + CSRF cookie/token echo.
- 생산용 RSA key는 AUTH_PRIVATE_KEY(PKCS#8), AUTH_PUBLIC_KEY(X.509), 선택 AUTH_PREVIOUS_PUBLIC_KEY 파일 경로를 사용. 개발 모드만 시작 시 ephemeral key를 생성(재시작 후 기존 access JWT는 무효; 유효 refresh로 복구 가능). 운영은 AUTH_DEV=false, HTTPS origin, Secure cookie, SMTP STARTTLS required, 비밀은 파일/Secret으로 주입한다. 이전 public key는 최대 token TTL 및 배포 전파 기간 이후 제거; 운영 key 배포/rotation runbook은 Phase 11.
- 가입·로그인·인증·재전송·회전 IP rate limit, 로그인/가입/재전송 계정별 limit, 인증된 API 사용자 120/min, 초대/수락 10/min. Redis Lua token bucket 서버 TIME 및 TTL; 로그인/보호 API Redis 장애 503 fail-closed. 원시 이메일/IP를 Redis key에 넣지 않는다(해시 기반; 저엔트로피 PII 해시는 익명화 아님). 운영 Edge에서 신뢰 가능한 프록시 IP 전달 규칙은 Phase 11.
- 프런트엔드는 공통 AuthClient로 메모리 bearer, refresh single-flight, 지원 브라우저에서 같은 origin 탭 Web Locks를 적용한다. 별도 localhost 포트들은 origin이 다르지만 cookie는 포트 구분이 없으므로 두 개발 포털을 다른 계정으로 동시에 테스트할 때는 별도 브라우저 프로필/host 사용. 네트워크 유실/독립 클라이언트 동시 회전은 strict replay 정책상 재로그인 필요할 수 있다.

## Refresh Token concurrency

~~~mermaid
sequenceDiagram
  participant A as Request A
  participant B as Request B
  participant D as MySQL
  A->>D: SELECT refresh_family FOR UPDATE
  B->>D: same family lock (wait)
  A->>D: consume old, insert next hash
  A->>D: COMMIT
  A-->>A: 200 + new cookie
  B->>D: re-read old used_at
  B->>D: revoke family and all tokens + audit
  B->>D: COMMIT
  B-->>B: 401 REFRESH_REPLAY
~~~

트랜잭션 내에서 replay 예외를 던지면 revocation까지 롤백될 수 있으므로 failure result를 반환하고 commit 이후 HTTP 401로 변환한다. 회전과 logout은 동일 family lock 순서를 사용. 이미 발급된 access JWT는 최대 10분 유효하나 현재 권한은 매번 DB 조회.

## 테넌트와 역할

- 인증된 이메일 사용자만 채널 생성; 생성자는 해당 tenant의 TENANT_ADMIN. 이것은 플랫폼 제작자 승인이나 전역 관리자 권한을 부여하지 않는다.
- tenant membership LEARNER < CREATOR < TENANT_ADMIN. outsider/탈퇴/회수된 membership은 404, 같은 tenant에서 부족한 역할은 403. global PLATFORM_ADMIN도 tenant 데이터에 암묵 접근 불가.
- tenant 생성은 actor row lock + scoped Idempotency-Key + request hash + 24h record, 동일 요청은 원래 tenant ID를 반환하면서 현재 접근 권한 재검사. 변경 payload 409.
- admin 초대는 지정 이메일·역할·48h 일회용 코드, 기존 pending 코드 폐기. 수락은 로그인·이메일 확인·정확한 대상 이메일 검증, 기존 membership의 역할을 자동 변경하지 않는다.
- 구성원 변경/회수: tenant row lock → actor 권한 확인 → target row/version 비교. If-Match 누락 428, stale 412. 마지막 활성 TENANT_ADMIN 제거 금지 409. 권한 변경은 다음 요청부터 적용.
- 제작자 승인: verified platform admin만 target user 승인 상태 GET/PUT; If-Match 필수. 실제 제작 가능 여부는 active creator-or-admin membership + verified email + creator_approved 모두 필요. 승인 회수는 즉시 guard에 반영. 실제 영상·이미지 빌드 권한 적용은 해당 기능 도입 때 guard 재사용.
- audit: 가입, 로그인 성공/실패, 인증, 채널·초대·구성원 변화, refresh replay/logout, 제작자 승인·회수 기록. 토큰/비밀번호/메일 원문/클라이언트 IP 기록하지 않음. 구성원 변경은 before/after role/status 포함.

## API

OpenAPI [v0.2](../api/openapi.yaml). /api/v1 기준.

| Method | Path | 권한 |
|---|---|---|
| GET | /auth/csrf | public |
| POST | /auth/register, /auth/login, /auth/resend-verification | CSRF + Origin |
| POST | /auth/verify-email | bearer + CSRF + Origin |
| POST | /auth/refresh, /auth/logout | cookie + CSRF + Origin |
| GET | /me, /tenants | bearer |
| POST | /tenants | verified bearer + Idempotency-Key |
| GET | /tenants/{tenantId} | active membership |
| GET | /tenants/{tenantId}/creator-capability | approved creator guard |
| GET | /tenants/{tenantId}/members | tenant admin |
| POST | /tenants/{tenantId}/invitations | tenant admin |
| POST | /invitations/accept | verified matching user |
| PUT | /tenants/{tenantId}/members/{userId} | tenant admin + If-Match |
| GET/PUT | /admin/users/{userId}/creator-approval | verified platform admin; PUT If-Match |

JWKS: /.well-known/jwks.json (Core root). tenant/member list는 현재 100개 제한의 초기 UI; cursor pagination은 대규모 사용 전 구현해야 한다. RFC 9457 problem, traceId, Retry-After. 영상/작업 등 기존 API 명세는 아직 설계 계약이며 구현 완료를 의미하지 않는다.

## 로컬 실행

~~~powershell
conda activate HongDev
docker compose --profile core up -d --build --wait
npm ci
npm run dev:admin
# 다른 터미널: npm run dev:learner
~~~

기존 .env 유지. Mailpit은 loopback 8025 기본, Windows 예약 충돌 시 MAILPIT_HOST_PORT 변경. 메일은 Mailpit 웹 UI에서 확인하고 가입 → 로그인 → 인증 코드 입력 → 채널 생성/초대 수락 순서. 실제 외부 메일 전송 없음. 플랫폼 관리자 초기 계정은 공개 API로 생성하지 않는다. 운영자가 인증된 계정에 대한 별도 승인 절차와 감사 기록을 거쳐 DB 권한을 부여할 수 있으며 실제 운영 provision은 Phase 11 runbook/승인 대상이다. 로컬 integration만 격리 DB fixture 사용.

## 검증

~~~powershell
conda run -n HongDev .\gradlew.bat --no-daemon check :services:core-api:bootJar :services:realtime-api:bootJar
conda run -n HongDev npm run build
conda run -n HongDev npm test
conda run -n HongDev python scripts/validate-contracts.py
~~~

실제 auth integration은 disposable Compose 프로젝트 dockvue-phase02-test에서만 DB fixture를 허용한다(또는 CI ephemeral). COMPOSE_PROJECT_NAME, CORE_HOST_PORT, REALTIME_HOST_PORT, LOCALSTACK_HOST_PORT, MAILPIT_HOST_PORT를 충돌 없는 값으로 설정한 뒤 Compose up. DOCKVUE_TEST_ALLOW_DB_FIXTURES=1, DOCKVUE_TEST_REDIS_OUTAGE=1, python scripts/auth-integration.py 실행. 테스트 프로젝트에 한해서 down -v 가능. 일반 dockvue 개발 DB의 삭제는 금지.

### 2026-11-26 로컬 검증 기록

- Phase 01 최종 GitHub Actions: 4/4 성공(run 45759708968).
- HongDev Gradle check/bootJar 성공: Core 4, Realtime 1 테스트. Angular 1, Vue 2 테스트 및 양쪽 프로덕션 빌드 성공.
- OpenAPI/JSON Schema 검증 성공. 기존 개발 DB V1→V2 마이그레이션 성공, 격리 Compose 신규 DB V1/V2 마이그레이션 성공 및 7개 컨테이너 정상 기동.
- 격리 Compose 내부 HTTP 통합 검사 종료 코드 0: 익명 접근 및 CSRF/Origin, 이메일 정규화·가입·Argon2id·로그인, 이메일 인증, RS256/JWKS, 채널 멱등성/IDOR, 초대/RBAC, 버전 충돌/마지막 관리자 보호/권한 회수, 동시 관리자 강등, 동시 Refresh Token 회전·재사용 시 전체 family 폐기, 로그아웃·감사 로그, Redis 요청 제한의 11개 그룹 통과.
- 격리 Redis stop/start 장애 실험: 로그인 503 fail-closed 확인, Redis 재기동 성공. 테스트용 DB와 볼륨만 삭제하고 일반 개발 DB는 보존하였다.
- Windows 호스트 경유 전체 인증 통합 검사는 서로 다른 요청에서 WinError 10054로 두 차례 중단되었다. 당시 API healthy/OOM false였으며 원인은 확정하지 못했다. Docker 내부 네트워크 경유 동일 시나리오로 검증을 완료했다. [TS-02](../troubleshooting/TS-02-refresh-rotation-and-test-transport.md)

실운영 전 password reset/account recovery, 계정 선점 복구, 다중 노드 키 운영, 메일 durable delivery/outbox, TLS/프록시, 보안 감사 및 취약점 검토가 필요하다. 목록 100건 제한은 후속 페이지네이션 대상이다. 브라우저 실제 화면 클릭 E2E 및 운영 AWS/SMTP는 이번 검증 범위가 아니다. Phase 02는 초기 기능 구현이지 프로덕션 보안 인증이 아니다.
