# ADR 0008 — Direct multipart and fenced VOD processing

상태: Phase 04 구현 결정. D00 승인 계약 유지.

- 대용량 업로드를 API proxy 대신 S3 presigned multipart로 분리한다. 서버가 ListParts/HEAD를 검증하고 브라우저 파트 SHA256, Worker 전체 SHA256을 독립적으로 확인한다. ETag는 해시로 가정하지 않는다.
- MySQL job/outbox 동시 커밋, SQS Standard at-least-once delivery. DB lease/fence로 중복 처리 및 stale 완료를 차단한다. 메시지 삭제는 Core의 완료/실패 확인 후 수행한다. exactly-once delivery를 주장하지 않는다.
- attempt별 불변 output prefix와 master-last 검증 후 current media pointer를 교체한다. 실패한 교체는 기존 READY pointer를 훼손하지 않는다. Release 고정 및 GC는 후속 단계.
- FFmpeg는 별도 non-root Python Worker, 제한된 자원과 디코더 네트워크 차단. 운영 IAM 및 완전한 sandbox 보안 검증은 후속 게이트.
- 로컬은 LocalStack의 scoped playback cookie 및 signed segment redirect. 운영용 OAC/CloudFront cookie를 흉내 내어 배포 완료라고 표시하지 않는다.
