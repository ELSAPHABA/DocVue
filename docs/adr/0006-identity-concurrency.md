# ADR 0006 — 인증 DB 트랜잭션과 이메일 전달

상태: Phase 02 구현 결정 (D00-01~08 변경 없음).

- 선택: Spring Security resource server + Nimbus RS256/JWKS, Argon2id, MySQL refresh family/token 분리, JdbcTemplate 명시적 행 잠금, Redis Lua token bucket. 대안: JWT refresh 자체 저장 없이 회전(폐기/재사용 검출 어려움), Redis-only refresh(영속성/복구 결합), 전역 JVM lock(다중 인스턴스 불가), 모든 작업 JPA entity pessimistic lock(가능하나 token CAS/lock 순서 가시성 낮음).
- 가족 행 단위 serialize 및 transaction commit 후 replay 401 변환. 모든 토큰에 raw secret 저장하지 않음. 네트워크 유실 시 재로그인 허용이라는 Phase 00 정책 유지.
- role/creator approval은 DB 현재 상태에서 확인; global admin과 tenant admin 분리. 마지막 admin 보호는 tenant row lock으로 직렬화.
- 이메일 코드는 로그인한 동일 계정의 검증에 사용한다. 개발 SMTP Mailpit(실제 외부 발송 없음). DB commit 이후 메일 발송 및 재전송; durable mail outbox는 후속 작업. 운영 초기 관리자 provisioning과 복구 기능은 공개 배포 전 보완.
- 정확한 사양과 실행 절차: [Phase 02](../architecture/Phase_02_Identity.md).
