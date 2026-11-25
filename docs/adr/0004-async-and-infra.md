# ADR 0004 — 비동기 작업과 AWS
상태: 제안. MySQL transactional outbox + SQS standard, lease/fence/idempotent jobs, FFmpeg/BuildKit workers 분리. 대안: Kafka(초기 운영 과다), 동기 API(timeout), Kubernetes(비용/복잡도). AWS Ubuntu EC2+S3+CloudFront+SQS+Terraform, Seoul 기본; 단일 control plane 초기 non-HA 명시. 실제 비용 승인 및 backup restore 전 공개 금지. 정확한 sizing은 Phase 11 측정.
