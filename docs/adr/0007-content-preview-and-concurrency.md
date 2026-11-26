# ADR 0007 — Content aggregate, metadata preview, keyset pagination

상태: Phase 03 구현 결정. 기존 승인 D00-01~08 변경 없음.

- Phase 편집은 individual mutation 대신 video aggregate timeline PUT. 장점: 모든 구간 검증·정렬을 원자적으로 처리하고 ETag 하나로 metadata/duration/Phase 간 충돌을 차단. 단점: 최대 20개를 한 번에 전송하고 서로 다른 Phase 동시 편집도 충돌할 수 있음. 미래 collaborative editor는 별도 설계.
- JPA @Version으로 video aggregate 관리, JdbcTemplate read projection + batch label query로 N+1 방지. Tenant row lock은 권한 회수·생성 멱등성·콘텐츠 편집을 일관된 순서로 직렬화한다. 규모에 따라 finer-grained lock 평가.
- 영상과 release가 준비되기 전에는 실제 발행 금지. 별도 명시적 metadata preview만 제공. PRIVATE 기본값, UNLISTED 목록 제외, PRIVATE membership 확인. preview progress는 실제 playback/validation과 분리.
- keyset pagination with HMAC scope binding. offset paging 대비 깊은 페이지 탐색의 건너뛰기 비용을 줄일 설계이나 실측 결과와 혼동하지 않는다. 본문 substring 검색은 작은 MVP 데이터에서 단순하게 사용하고, 데이터 규모 증가 시 FULLTEXT/검색 엔진을 평가.
- 브라우저 검증은 Playwright Chromium headless로만 수행한다. CI에서도 동일 테스트를 실행. 개발 사이트 실행과 테스트용 브라우저 실행은 별개.
