# ADR 0004 — 비동기 작업과 AWS
상태: 승인 (Phase 00 설계 기준선, 2026-11-26; 구현·실측 검증은 후속 Phase). MySQL transactional outbox + SQS standard, lease/fence/idempotent jobs, FFmpeg/BuildKit workers 분리. 대안: Kafka(초기 운영 과다), 동기 API(timeout), Kubernetes(비용/복잡도). AWS Ubuntu EC2+S3+CloudFront+SQS+Terraform, Seoul 기본; 단일 control plane 초기 non-HA 명시. 실제 비용 승인 및 backup restore 전 공개 금지. 정확한 sizing은 Phase 11 측정.

D00-02/03 확정: 초기 DB는 EC2 자체 운영; 관리형 전환은 비용·가용성 근거와 승인 필요. 승인된 제작자만 초기 컨테이너 콘텐츠 발행.
