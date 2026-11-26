# TS-01 — Windows TCP 예약 범위와 Docker 호스트 포트 충돌

- 발생: 2026-11-26, Phase 01 로컬 검증
- 환경: Windows 10 19045, Docker Desktop Linux/amd64 Engine 29.9.3, Compose 5.7.1, Conda HongDev
- 분류: 실제 발생 장애 (fault injection 아님)

## 증상 및 재현

`docker compose --profile core up -d --build --wait --wait-timeout 300` 실행 시 Core API와 Realtime API 이미지 빌드는 성공하였으나 LocalStack 포트 바인딩에서 실패했다 (exit 1).

~~~text
ports are not available: exposing port TCP 127.0.0.1:4566
listen tcp4 127.0.0.1:4566: bind: An attempt was made to access a socket in a way forbidden by its access permissions.
~~~

Compose는 나머지 컨테이너의 기동도 중단했다. 백엔드 컴파일 오류나 S3/SQS 초기화 오류가 아님을 빌드 결과로 구분했다.

## 원인 조사

~~~powershell
netsh interface ipv4 show excludedportrange protocol=tcp
Get-NetTCPConnection -State Listen
~~~

실제 확인된 Windows TCP 제외 범위는 4566(4520–4619), 8080(7981–8080), 8081(8081–8180), 4200(4120–4219), 5173(5141–5240)을 포함했다. 단순히 LISTEN 프로세스가 없는지 확인하는 것만으로는 포트 바인딩 가능 여부를 판단할 수 없다. loopback TcpListener의 실제 bind 시도로 대체 포트 18080, 18081, 24566, 24200, 15173의 사용 가능성을 확인했다. 예약을 설정한 OS 구성요소의 정확한 주체는 조사하지 않았으므로 Hyper-V/WSL이 원인이라고 단정하지 않는다.

## 해결

Windows 예약 범위 삭제, 방화벽 변경, 다른 컨테이너 중지 대신 호스트 포트를 설정 가능하게 분리했다. `.env.example` 기본값 및 컨테이너 내부 포트는 유지한다. 로컬 `.env`에만 대체 포트를 설정한다.

| 환경 변수 | 기본 | 검증 환경 |
|---|---:|---:|
| CORE_HOST_PORT | 8080 | 18080 |
| REALTIME_HOST_PORT | 8081 | 18081 |
| LOCALSTACK_HOST_PORT | 4566 | 24566 |
| ADMIN_HOST_PORT | 4200 | 24200 |
| LEARNER_HOST_PORT | 5173 | 15173 |

Compose port binding은 loopback으로 제한하며 DB는 호스트에 노출하지 않는다. `scripts/dev.mjs`는 같은 `.env`에서 포트만 읽고, Angular/Vue 프록시에 Core API 포트를 전달한다. Vite는 포트 자동 변경 대신 strictPort 사용. `scripts/smoke.py`는 Compose에서 실제 host binding을 조회하므로 하드코딩된 포트에 의존하지 않는다. `scripts/smoke-frontends.py`는 자체적으로 시작한 개발 서버에만 접근하고 종료한다.

## 검증 및 결과

- 기존 이미지 재사용 후 `docker compose --profile core up -d --no-build --wait --wait-timeout 300`: exit 0, 6/6 healthy.
- `conda run -n HongDev python scripts/smoke.py`: exit 0, API readiness/liveness/status, Flyway seed, Redis/MongoDB, S3 4개 버킷 및 SQS 2개/DLQ 정책 검사 통과 (11개 PASS 출력).
- `conda run -n HongDev python scripts/smoke-frontends.py`: exit 0, Angular 24200/Vue 15173의 HTML 응답과 Core API 프록시 JSON 응답 검사 통과.

Before: 이미지 빌드 성공, 로컬 stack 기동 실패. After: 동일 컨테이너 포트 및 서비스 연결 계약을 유지하면서 전체 stack 기동과 통합 검사 성공. 이 결과는 가용성 문제 해결이며 처리량 향상이나 성능 개선 수치를 주장하지 않는다.

재발 시 `netsh`와 실제 bind를 재확인하고 `.env` 포트만 변경한다. 예약 범위는 재부팅·OS 설정에 따라 달라질 수 있다.
