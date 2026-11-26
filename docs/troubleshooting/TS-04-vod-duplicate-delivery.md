# TS-04 — VOD 중복 전달과 검증 환경

## 의도적 fault injection: SQS 완료 메시지 재전송

격리 Compose `dockvue-phase04-test`, LocalStack SQS/실제 FFmpeg 7.1.5. 17 MiB(2 parts) 합성 MP4의 변환이 성공한 뒤 동일 jobId를 큐에 재전송했다. 8초 뒤 큐의 visible/in-flight approximate counts가 모두 0이고 DB job은 `SUCCEEDED`, `attempt=1`, `fence=1`로 유지되었다. 중복 claim HTTP 204, stale heartbeat HTTP 409 또한 Playwright E2E에서 확인했다. 이는 완료된 작업 재전송 실험이며 모든 분산 장애 조건에서 exactly-once를 보장한다는 뜻은 아니다.

## 실제 문제 및 해결

- Java AWS SDK 모델명 `Part`/`Progress` 충돌: 명시적인 import로 수정, Gradle 컴파일 및 테스트 성공.
- 초기 worker base의 FFmpeg 5.1.9는 7.1+ 계약 미충족: Debian trixie 기반 이미지 digest와 FFmpeg/FFprobe 7.1.5 버전 고정, Docker build 및 12개 Worker 테스트 성공.
- fixture 생성 스크립트를 `docker compose cp`로 `/tmp`에 복사하면 root 소유 파일 삭제가 non-root에서 거부됨: CI는 스크립트를 stdin으로 전달하고 생성된 MP4만 복사/삭제한다.
- Windows 호스트 연결 reset은 Phase 03의 미해결 장애; 이번에는 격리 Docker 내부 headless Chromium을 선택했다. Windows host 전체 E2E를 성공으로 기재하지 않는다.
