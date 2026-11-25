# Phase 01 — Bootstrap

Status: implementation candidate, runtime gate pending. No AWS resources created.

## Local start (Linux/macOS/WSL with Docker Desktop or Docker Engine)
Requirements: Docker Compose v2, Java 21, Node 24, npm, curl, bash. Docker resources suggested 4 CPUs / 8 GB RAM.

~~~bash
cp .env.example .env
docker compose --profile core up -d --build --wait
bash scripts/smoke.sh
npm install
npm run dev:admin
# in another terminal
npm run dev:learner
~~~

Creator http://127.0.0.1:4200 ; Learner http://127.0.0.1:5173 ; Core http://127.0.0.1:8080 ; Realtime http://127.0.0.1:8081. Databases only accessible on Compose network. LocalStack is a development emulator with synthetic credentials; not real AWS. Frontends are connection/status examples, not auth or content CRUD.

~~~bash
bash scripts/bootstrap-gradle.sh check
npm run build
npm test
python -m venv .venv
source .venv/bin/activate
pip install -r scripts/requirements-contracts.txt
python scripts/validate-contracts.py
docker compose --profile core down
~~~

Do not use down -v unless deliberately deleting local DB data. CI uses an ephemeral environment and deletes its own volumes.

## Pending gate
- Actual Gradle/npm resolution and wrapper generation, committed dependency lockfiles and container image digest pinning.
- Docker clean clone smoke, Angular/Vue build/test, OpenAPI formal validation; CI results required.
- Current executor has Java 21 and Node 22, no Docker/Gradle, external Git DNS fails, remote development tunnel unavailable. No successful runtime build claimed.
- Version selections are candidates; correct any incompatibility using official dependency matrix and evidence before closing Phase 01. No Phase 02 features yet.
