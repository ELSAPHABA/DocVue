# ADR 0001 — 서비스 경계와 데이터
상태: 승인 (Phase 00 설계 기준선, 2026-11-26; 구현·실측 검증은 후속 Phase). Spring MVC + JPA/MySQL은 업무 상태의 단일 작성자, WebFlux는 실시간 연결, MongoDB 이벤트, Redis 캐시/PubSub. 대안: 단일 MVC 앱, 전면 reactive, 모든 서비스별 DB. 선택 이유: blocking JDBC와 이벤트 루프 분리, 초기 운영 복잡성 제한, 실제 reactive 부하 검증. 비용: 서비스 간 계약/재연결/관측 필요. Phase 10 부하 검증 후 재평가.
