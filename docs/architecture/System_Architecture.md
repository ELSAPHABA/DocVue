# DockVue 시스템 아키텍처

상세 계약: [Technical Contracts](../Technical_Contracts.md). 제품 구현 전 목표 구조.

## Context / Container
~~~mermaid
flowchart TB
  C[Creator Angular CMS] --> E[HTTPS Edge]
  L[Learner Vue Portal] --> E
  CLI[Local CLI] --> E
  E --> A[Spring MVC Core]
  E --> W[Spring WebFlux]
  A --> SQL[(MySQL)]
  A --> X[(Redis)]
  A --> O[Outbox relay]
  O --> Q[SQS media/build + DLQ]
  Q --> M[FFmpeg worker]
  Q --> B[Isolated build worker]
  M --> A
  B --> A
  M --> S[(S3 media)]
  B --> T[(S3 artifacts)]
  B --> ST[Private staging registry]
  ST --> R[Published OCI Registry]
  R --> RS[(S3 registry)]
  S --> CDN[CloudFront]
  CDN --> L
  A --> X
  W --> X
  W --> N[(MongoDB events)]
  W --> A
  CLI --> R
  CLI --> T
  CLI --> D[Local Docker Compose]
  D --> AI[User AI tool / Harness]
~~~
Control plane: identity, metadata, state transitions; data plane: S3/CDN/Registry direct transfers. Worker cannot mutate SQL directly. realtime uses state/replay endpoints with service identity. AI tool is outside platform trust boundary.

## Publication and recovery
~~~mermaid
sequenceDiagram
  participant C as Core
  participant W as Worker
  participant R as Private staging
  participant P as Published registry
  participant S as S3 artifacts
  participant D as MySQL
  C->>D: release/job/outbox
  W->>C: claim + fence
  W->>R: build, scan, restore
  W->>C: validated digests + report
  C->>D: approved READY to PUBLISHING
  W->>P: promote approved images
  W->>S: immutable bundle and manifest
  W->>C: completion with fence
  C->>D: CAS current release + outbox
~~~
External side effects are not in SQL transaction. Approved images may be accessible before pointer flip via repository scope; no embargo or answer content in published repo. Reconcile unfinished publish attempts; previous current release remains until successful CAS.

## Environments / AWS
| 환경 | 배치 |
|---|---|
| local | Docker Compose, local DB volumes, S3/SQS emulator, registry filesystem emulator, fake credentials |
| CI | ephemeral Testcontainers + isolated runner; no prod secrets |
| staging | actual AWS S3/SQS/CloudFront, limited EC2; IAM and multipart/CDN smoke |
| prod | ap-northeast-2, VPC two AZ public ALB, private Ubuntu application/registry and workers, private DB; CloudFront OAC S3 |

AWS: Route53/ACM, separate domains app/admin/registry, ALB path /api/v1 and /events, private services with SG only required ports. S3 gateway endpoint, other AWS egress via cost-reviewed NAT/endpoints. No public DB/SSH; SSM session manager, IAM instance profiles. Registry auth token realm TLS. Separate staging/public registry credentials; worker disposable per build, deny IMDS and private network, narrowly scoped artifact upload broker. Initial deploy can be single EC2 control plane (non-HA explicitly) and separate build host; Multi-AZ RDS and additional API nodes when budget approved. No Kubernetes initially. Terraform state encrypted remote backend and lock, env separation; no credentials in state where avoidable.

## Operational flow
PR validation → pinned builds → scan/test → staging smoke → approval → deploy → health/rollback. Outbox, DLQ, publication reconciliation, DB backup restore, S3 version retention, Registry GC dry run. Observability uses metrics/log/trace with IDs, no unbounded label cardinality. P2 live ingest/media origin separate from VOD; Android playback and legacy migration independent.
