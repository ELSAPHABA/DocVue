# TS-03 — 콘텐츠 조회 정합성과 브라우저 전송 장애

## 조회 및 동시성 설계 실험

영상 목록을 조회할 때 JPA lazy collection을 건별로 불러오면 N+1 네트워크 왕복이 생길 수 있다. 구현에서는 video/tenant 및 phase count projection 1회 + label IN batch 1회로 목록을 구성했다. 이는 설계상의 예방이며, 이전 취약 구현의 before/after latency를 측정한 것은 아니다.

실제 격리 MySQL에서 PUBLIC + preview_enabled + DRAFT + 활성 tenant, catalog_at/id DESC LIMIT 조건을 사용한 기본 EXPLAIN은 videos의 ix_video_catalog (range, Using index condition), tenants의 PRIMARY (eq_ref)를 선택했다. 작은 테스트 데이터이므로 전체 검색 필터 및 대규모 성능을 보증하지 않는다. 추후 성능 Phase에서 고정된 데이터/부하로 측정한다.

타임라인은 video aggregate version, tenant/actor/video lock, ordered non-overlap validation, ordinal offset +20의 두 단계 변경으로 전체 정합성을 보호한다. 실제 E2E와 HTTP 통합에서 stale edit 412, 겹침/기간 축소 422, 다른 영상 Phase ID 거절, 생성 멱등성/충돌을 확인했다.

## 실제 브라우저 장애

Windows HongDev headless Chromium 최초 실행: CSRF 200, 회원가입 202, 로그인 응답 ERR_CONNECTION_RESET. MySQL audit에는 LOGIN 성공이 있었다. 컨테이너 healthy. 로그인 성공 이후 응답이 유실된 것이므로 클라이언트 인증 오류로 단정하거나 무조건 재시도하지 않았다. OS/Docker host forwarding reset의 원인은 아직 미확정이다.

Docker 내부 네트워크의 pinned Playwright 이미지에서 같은 headless E2E를 실행하여 통과했다(1 scenario, 12.3s; 전체 33.2s). 이는 호스트 경로의 원인 해결을 의미하지 않는다. screenshot 4개와 테스트 결과는 로컬 test-results/internal 경로에 저장했다. 사용자가 요청한 대로 headed 모드/브라우저 창을 열지 않았다.
