# DockVue 데이터베이스 설계

MySQL 8.4, InnoDB, utf8mb4, UTC DATETIME(6), UUID CHAR(36) ascii_bin (v4 generated server; optimize binary representation only after measurements). Flyway migrations; ddl-auto=validate except isolated tests. Logical schema below; migration code in Phase 01–03.

Phase 02 실제 인증 스키마는 V2__identity.sql을 기준으로 한다. users, tenants, memberships, email_verifications, refresh_families, refresh_tokens, invitations, idempotency_keys, audit_events를 추가했다. refresh_families 행 잠금으로 회전과 재사용 폐기를 직렬화하며, 토큰 원문은 저장하지 않는다. 영상 및 콘텐츠 테이블은 Phase 03 이후에 구현한다. [인증 설계](Phase_02_Identity.md).

Phase 03 실제 V3__content.sql: videos(@Version), video_repositories(영상당 unique), video_labels(정규화 tag/stack), phases(tenant/video composite FK, ordinal/range 제약), preview_progress(본인·video version 기반 학습 시작 위치). 현재 preview_progress는 future release-bound progress/playback_progress를 대체하지 않으며, media_versions/releases 및 관련 pointer는 후속 Phase에서 추가한다. [콘텐츠 설계](Phase_03_Content.md).

~~~mermaid
erDiagram
  USERS ||--o{ MEMBERSHIPS : has
  TENANTS ||--o{ MEMBERSHIPS : has
  TENANTS ||--o{ VIDEOS : owns
  VIDEOS ||--|| VIDEO_REPOSITORIES : has
  VIDEOS ||--o{ PHASES : drafts
  VIDEOS ||--o{ RELEASES : versions
  RELEASES ||--o{ RELEASE_PHASES : freezes
  RELEASE_PHASES ||--o{ ARTIFACTS : contains
  VIDEOS ||--o{ UPLOAD_SESSIONS : accepts
  VIDEOS ||--o{ JOBS : processes
  RELEASES ||--o{ JOBS : builds
  USERS ||--o{ PROGRESS : learns
  RELEASE_PHASES ||--o{ PROGRESS : tracks
  USERS ||--o{ REFRESH_TOKENS : authenticates
  TENANTS ||--o{ AUDIT_EVENTS : records
~~~

| Table | Fields / constraints | Indexes / access |
|---|---|---|
| users | id PK, normalized_email UQ, password_hash, status, platform_role, created_at, version | email unique |
| tenants | id PK, slug UQ, name, status, quota_json, version | slug unique |
| memberships | tenant_id + user_id PK/FK, role, status, version | user_id,status,tenant_id |
| invitations | id PK, tenant_id, email, token_hash UQ, role, expires_at, consumed_at | tenant_id,email,status |
| refresh_tokens | id PK, user_id FK, family_id, token_hash UQ, expires_at, used_at, revoked_at, replaced_by | family_id, revoked_at |
| videos | id PK, tenant_id FK, title, description, visibility, status, duration_ms, current_release_id nullable, current_media_version_id nullable, published_at, version | (tenant_id,status,published_at,id), (visibility,status,published_at,id), UNIQUE(tenant_id,id) |
| media_versions | id PK, tenant_id, video_id FK, original_key, full_sha256, duration_ms, hls_prefix, status | (video_id,status), UQ(video_id,id) |
| phases | id PK, tenant_id, video_id FK, ordinal, start_ms,end_ms,title,objective,version | UQ(video_id,ordinal), (video_id,start_ms), UQ(video_id,id) |
| video_repositories | video_id PK/FK, tenant_id, registry_name UQ, status | tenant_id |
| releases | id PK, tenant_id, video_id FK, media_version_id FK, semver, state, manifest_key, manifest_sha256, published_at, version | UQ(video_id,semver), UQ(video_id,id), (video_id,state) |
| release_phases | id PK, release_id FK, video_id, source_phase_id, ordinal, start_ms,end_ms,title,objective | UQ(release_id,ordinal), UQ(release_id,source_phase_id) |
| artifacts | id PK, release_phase_id FK, kind, relative_path, object_key, size_bytes, sha256, image_ref nullable, platform nullable | UQ(release_phase_id,relative_path), (sha256) |
| upload_sessions | id PK, tenant_id, video_id, actor_id, object_key UQ, provider_upload_id, expected_bytes, expected_checksum, status, expires_at, version | (status,expires_at), (video_id,status) |
| jobs | id PK, tenant_id, video_id, release_id nullable, media_version_id nullable, type,state,attempt,fence,lease_until,deadline,request_key,output_prefix,error_code,version | UQ(tenant_id,type,request_key), (state,lease_until), (release_id,state) |
| outbox_events | event_id PK, tenant_id, aggregate_type/id/version, event_type, payload JSON, delivery_state, attempt, lease_until, next_attempt_at, occurred_at | UQ(aggregate_type,aggregate_id,aggregate_version,event_type), (delivery_state,next_attempt_at,lease_until) |
| idempotency_keys | actor_id,tenant_scope,route,key composite PK, request_hash, state,response_json,expires_at | expires_at |
| progress | user_id,release_phase_id composite PK, status,last_position_ms, verified_at, version | (user_id,verified_at) |
| playback_progress | user_id,video_id,media_version_id PK, position_ms,version | (user_id,video_id) |
| audit_events | id PK, tenant_id nullable, actor_id nullable, action,target_type,target_id,trace_id,occurred_at, sanitized_details JSON | (tenant_id,occurred_at,id), (target_type,target_id,occurred_at) |

Tenant-owned tables duplicate tenant_id where necessary for composite FK (tenant_id,video_id) → videos(tenant_id,id), release/media pointer references must belong to same video (video_id,id) composite FK; circular current pointer added after creation. Artifact OCI image entries use logical path images/{service}/{platform}; JSON manifest authoritative bytes stored S3, DB derived normalized references for audit/GC. Release Phase stores frozen fields independent of draft phase deletion; source_phase_id historical identifier, no cascading delete. Physical delete restricted, audit retained. Private membership policy in security doc. Invitation status derived or stored explicitly in migration.

## Invariants / transactions
- Phase non-overlap requires serializing modifications by SELECT video FOR UPDATE; validate range 0 <= start < end <= media duration if known; draft without media allows provisional ranges; publication rechecks all. Reorder transaction handles unique ordinal with temporary offset; canonical contiguous 1..N at release.
- Read committed for application writes, InnoDB explicit locks and unique constraints. No remote I/O during DB locks. Optimistic version CAS for edits/current pointer. Jobs fencing token increments on new claim; stale completion rejected. Outbox commit in same transaction as state.
- release snapshot immutable; state may change only allowed transitions. Approved artifact metadata freeze; never cascade delete published data. Rollback pointer only. Current release change requires same video and valid PUBLISHED, with media version pinned.
- query plans verified with data volume: catalog keyset, tenant filter, batch membership lookup; avoid N+1; no premature index every column.
- MySQL primary source; Redis cache never authorizes stale private resource.

## MongoDB
Collections: build_events (unique eventId, jobId+aggregateVersion, tenantId+occurredAt), playback_events (unique eventId, videoId+occurredAt), lab_events (opt-in, user pseudonym, phase/release, result only), chat_messages P2 (roomId+sequence unique). TTL on separate expiresAt field, defaults 30d telemetry, 7d raw progress logs, retention for chat configured; TTL deletion asynchronous. Bounded documents (64KiB), bulk write, aggregation explain. Mongo event store not authoritative job state; missing event replay reads durable Core state/history.

## Redis
Namespaced keys dv:{env}:cache:video:{id}, dv:{env}:rl:{bucket}, dv:{env}:presence:{room}, dv:{env}:job:{id}; no raw PII in key. Cache TTL 60s+jitter; authorization checks DB on sensitive issuance. Lua rate limit atomic; Redis Pub/Sub transient only. No cross-service shared mutable business state. Test Redis outage/reconnect and TTL stampede.
