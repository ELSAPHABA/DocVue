# Phase 01 — Bootstrap

상태: 구현 및 로컬 개발 환경 검증 완료 (2026-11-26). 제품 인증·콘텐츠·스트리밍은 아직 미구현. 실제 AWS 리소스 생성 없음.

## 실행 환경

Java 21, Node 24, npm, Python 3.10+ (CI 3.12), Docker Engine / Compose. Docker 권장 개발 자원 4 CPU / 8 GB RAM. Windows Conda HongDev에서 검증한 버전: OpenJDK 21.0.13.1 (Zulu), Gradle 8.14.4, Node 24.21.0, npm 12.2.2, Python 3.10.20, Docker Engine 29.9.3 (Linux/amd64), Compose 5.7.1. 시스템 Java 27을 변경하지 않았다. OpenJDK 및 계약 검사 패키지는 HongDev 내부에 설치하였다. Python 3.10은 지원 종료 이후 버전이므로 신규 개발 환경은 지원 중인 Python 3.12 이상을 권장하되 기존 HongDev의 Python 버전은 유지하였다.

## Windows / PowerShell

~~~powershell
conda activate HongDev
if (!(Test-Path .env)) { Copy-Item .env.example .env }
python -m pip install -r scripts/requirements-contracts.txt
docker compose --profile core up -d --build --wait --wait-timeout 300
python scripts/smoke.py
npm ci
python scripts/smoke-frontends.py
./gradlew.bat --no-daemon check :services:core-api:bootJar :services:realtime-api:bootJar
npm run build
npm test
python scripts/validate-contracts.py
~~~

HongDev에 Java 21이 없는 경우 `conda install -n HongDev -c conda-forge openjdk=21` 사용. Gradle Wrapper는 배포 파일 SHA-256을 확인하며 별도의 시스템 Gradle 설치는 필수가 아니다. 기존 환경을 업그레이드하기 전 다른 프로젝트 영향 검토. `GRADLE_USER_HOME`은 필요에 따라 Conda 환경 내부 경로로 설정할 수 있다.

## Linux/macOS/WSL

~~~bash
test -f .env || cp .env.example .env
docker compose --profile core up -d --build --wait --wait-timeout 300
python3 scripts/smoke.py
npm ci
python3 scripts/smoke-frontends.py
./gradlew --no-daemon check :services:core-api:bootJar :services:realtime-api:bootJar
npm run build
npm test
python3 -m venv .venv
source .venv/bin/activate
pip install -r scripts/requirements-contracts.txt
python scripts/validate-contracts.py
~~~

## 개발 서버 / 포트

`npm run dev:admin`과 `npm run dev:learner`는 별도 터미널에서 실행. `scripts/dev.mjs`가 프로젝트 루트 `.env`의 포트 설정을 사용한다. 서비스 주소는 loopback only; DB는 Compose 내부 네트워크에 한정. 시스템 환경 변수가 `.env`보다 우선한다. LocalStack은 가짜 인증정보를 사용하는 AWS 에뮬레이터다.

| 서비스 | 기본 호스트 포트 | Windows 예약 충돌 환경에서 검증한 포트 | 환경 변수 |
|---|---:|---:|---|
| Core API | 8080 | 18080 | CORE_HOST_PORT |
| Realtime API | 8081 | 18081 | REALTIME_HOST_PORT |
| LocalStack | 4566 | 24566 | LOCALSTACK_HOST_PORT |
| Angular CMS | 4200 | 24200 | ADMIN_HOST_PORT |
| Vue Portal | 5173 | 15173 | LEARNER_HOST_PORT |

Windows에서 `netsh interface ipv4 show excludedportrange protocol=tcp`로 예약 범위를 확인한다. 포트 충돌 시 `.env`의 숫자만 변경하고 컨테이너 내부 포트는 변경하지 않는다. [원인 및 검증 기록](../troubleshooting/TS-01-windows-port-exclusion.md).

`docker compose --profile core down`으로 개발 stack을 종료한다. 데이터 삭제가 필요한 경우에만 `down -v`를 사용한다. CI는 자체 임시 볼륨만 삭제한다.

## 재현 가능한 빌드

- Gradle Wrapper 8.14.4 공식 distribution 및 wrapper JAR SHA-256 검증, 두 서비스 Gradle Lockfile과 Spring Boot 4.1.3 BOM 사용. Dockerfile도 동일 lockfile 복사.
- Angular 21.2.0, Vue 3.5.28, TypeScript 5.9.3, RxJS 7.8.2, Vite 8.0.0 및 Pinia 3.0.4를 npm workspace에 고정; `package-lock.json`과 `npm ci` 사용.
- MySQL, MongoDB, Redis, LocalStack 및 두 Java base image는 실제 해석된 OCI SHA-256 digest로 고정 (compose.yaml, java.Dockerfile). Dockerfile의 Ubuntu apt 저장소 패키지 상태까지 bitwise 재현을 보장하지는 않는다.
- 의존성 취약점·라이선스 전수 검토, 실제 AWS 서비스, registry/media/builder/CLI, macOS/ARM64 검증은 후속 단계. npm 12의 일부 install script 기본 차단 경고가 있었지만 빌드 및 테스트는 통과; 일괄 allow 설정은 하지 않았다.

## 검증 기록

기준 초기 구현 커밋 `1ace93ed3a83ade7434e7b41f3fa81f18cb5f810`: [GitHub Actions 실행 45679697472](https://github.com/ELSAPHABA/DocVue/actions/runs/45679697472) contracts/java/frontend/smoke 모두 성공. 로컬 수정 이후 추가 검증은 아래와 같다. 이전 CI 결과를 새 커밋의 CI 결과로 간주하지 않는다.

| 검증 | 결과 |
|---|---|
| HongDev `python scripts/validate-contracts.py` | PASS (정식 OpenAPI 3.1, JSON Schema, 예시) |
| `python -m pip check` | PASS |
| Gradle check/bootJar --write-locks 및 wrapper 생성 | PASS |
| `gradlew.bat` lockfile 적용 check/bootJar | PASS, 두 JUnit 테스트 각 1/1 |
| `npm ci`, `npm run build`, `npm test` | PASS, 두 Vitest 테스트 각 1/1 |
| Compose 설정 및 기동 (포트 예약 해결 후) | PASS, 6/6 healthy |
| `python scripts/smoke.py` | PASS, 11개 출력: 두 API, Flyway, Redis, MongoDB, S3 4개, SQS/DLQ 2개 |
| `python scripts/smoke-frontends.py` | PASS, 두 개발 서버 HTML 및 Core API 프록시, Angular Realtime health 프록시 |
| 고정된 base image 및 Gradle Lockfile로 Docker 재빌드 + 기동 + smoke | PASS, 두 이미지 재빌드 성공, 6/6 healthy, 11개 smoke 재통과 |

로컬 Gradle·npm 테스트 및 Docker smoke는 Phase 01 범위의 최소 연결/초기화 검증이며 기능·보안·성능 전체 검증을 뜻하지 않는다. Windows 호스트의 실제 장애를 재현하여 설정 개선 후 해결하였다. CI는 새 commit의 별도 실행 결과를 확인한다. Phase 02의 제품 기능은 착수하지 않는다.
