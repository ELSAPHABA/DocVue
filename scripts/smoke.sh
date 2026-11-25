#!/usr/bin/env bash
set -euo pipefail
for port in 8080 8081; do
  curl --fail --silent --show-error --retry 30 --retry-all-errors --retry-delay 2 "http://127.0.0.1:$port/actuator/health/readiness"
  echo
  curl --fail --silent --show-error "http://127.0.0.1:$port/api/v1/status"
  echo
done
docker compose exec -T mysql sh -c 'mysql -u dockvue -p"$MYSQL_PASSWORD" dockvue -e "SELECT schema_label FROM platform_schema_metadata WHERE id=1"'
docker compose exec -T localstack awslocal s3api head-bucket --bucket dockvue-artifacts
docker compose exec -T localstack awslocal sqs get-queue-url --queue-name dockvue-build
