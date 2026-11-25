# DockVue 보안 아키텍처

## Trust boundaries / threat model
| 경계 | 위협 | 기본 방어 / gate |
|---|---|---|
| Browser/API | XSS/CSRF/IDOR | CSP, output escaping, origin/CSRF, JWT audience, tenant-filtered DB |
| Registry/S3/CDN | scope escalation, stale token, leaked URL | TLS, private buckets, short scoped token, signed cookie, no signed URL logging |
| Creator build | malicious Dockerfile, SSRF, secret exfiltration | disposable isolated VM/runner, no host socket/IMDS/production secrets, deny internal network, resource/time quota |
| Learner host | malicious image/compose/archive | signature+digest, review, normalized policy, no privileged/host socket, bind path confinement, VM for untrusted high risk |
| AI harness | prompt injection, unintended command | untrusted content, no automatic permission escalation, explicit user approval, no secrets |
| CI/CD | supply chain, PR secret theft | pinned actions, protected environment, OIDC short-lived, SBOM and scans |
| telemetry | sensitive logs or source leak | opt-in, minimization, retention, redaction, ACL |

## Access matrix
| Operation | Guest | Learner | Creator | Tenant admin | Platform admin |
|---|---|---|---|---|---|
| public/unlisted playback | yes (unlisted link) | yes | yes | yes | yes |
| private playback/lab | no | tenant member | own tenant | own tenant | no implicit |
| public lab credential | no | yes | yes | yes | yes |
| draft/phase edit | no | no | own tenant, own content or delegated | own tenant | no implicit |
| publish | no | no | authorized own content | own tenant | no implicit |
| role/quota | no | no | no | own tenant | global quota/admin |
| platform ops | no | no | no | tenant metrics | operational actions audited |

Creator ownership recorded on videos (created_by FK); tenant admin can delegate. Check current policy on sensitive actions. JWT refresh rotation and rate limit details in [contracts](../Technical_Contracts.md).

## Build and local execution policy
Initial author onboarding admin-approved (D00-03); arbitrary public creator uploads/build/publish are disabled. Expansion requires isolated build gate tests and a separate approved policy change. Image scanner alone insufficient; reproducible verification, SBOM, secret scan, provenance/signature, manual approval when risk. Build VM uses time/CPU/RAM/disk limits, no host docker.sock, egress deny by default; dependency mirror allowlist and no internal IP/metadata; dedicated credentials with short TTL via artifact broker; no access to production DB. Validate build contexts and archives without following links; reject traversal, symlink/hardlink escape, decompression bombs. No shared privileged build runners.

Local Docker Compose policy: parse normalized config after interpolation, deny host namespaces/privileged/devices/cap_add/docker socket/arbitrary bind mounts, external networks and volume drivers, include/extends outside approved workspace; restrict ports to loopback, resource caps and default no outbound Internet except approved tasks. Rootless/least privilege when compatible; DB init scripts may need constrained service UID; document exceptions. Never silently start downloaded code. Show plan and user confirmation. A container is not a security boundary equivalent to VM; recommend isolated VM for untrusted labs. Offline archive verify hash and image ID, online verify digest and signed manifest.

Registry per-repo scope implies all released phases share authorization; staging/answer repositories not accessible to learner. Published approved blobs may be reachable during promotion even before catalog pointer; no secrets or embargo content in learner repo. Already downloaded bytes cannot be revoked. Revoke new credentials, short TTL and remove catalog pointer; strict per-version revocation needs different architecture.

## Security gates
- negative authorization tests for every object/API, Registry token scope, S3 presign, CDN private HLS.
- secret scan + SBOM + CVE policy (critical/high block unless documented time-limited exception approved; no secret exceptions); signing trusted key outside build VM.
- audit login failure, membership changes, release publish/revoke, credential issuance, admin retry; redact secrets/tokens/PII. Backups encrypted, restore test; key rotation and incident runbook.
- no production spend or open public creator platform before Phase 11 security review. No security guarantee based solely on prompt instructions.

D00-03 확정: 초기 컨테이너 발행은 승인된 제작자만 허용한다. D00-07/08: 동일 영상의 발행된 Phase/Release 접근 정책은 동일하며, 학습자 실습은 로컬 Docker에서 수행한다.
