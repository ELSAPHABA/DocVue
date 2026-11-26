CREATE TABLE media_versions (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 video_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 original_key VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 expected_bytes BIGINT NOT NULL, full_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 duration_ms BIGINT, hls_prefix VARCHAR(255), status VARCHAR(24) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_media_video(video_id,id), FOREIGN KEY(tenant_id,video_id) REFERENCES videos(tenant_id,id),
 CHECK(expected_bytes BETWEEN 1 AND 5368709120)
) ENGINE=InnoDB;
ALTER TABLE videos ADD COLUMN current_media_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
 ADD CONSTRAINT fk_video_media FOREIGN KEY(id,current_media_version_id) REFERENCES media_versions(video_id,id);
CREATE TABLE upload_sessions (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 video_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 actor_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 media_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 provider_upload_id VARCHAR(1024), filename VARCHAR(255) NOT NULL,
 request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 status VARCHAR(24) NOT NULL, expires_at DATETIME(6) NOT NULL,
 job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_upload_key(actor_id,video_id,request_key),
 FOREIGN KEY(tenant_id,video_id) REFERENCES videos(tenant_id,id),
 FOREIGN KEY(video_id,media_id) REFERENCES media_versions(video_id,id),
 FOREIGN KEY(actor_id) REFERENCES users(id),
 INDEX ix_upload_expiry(status,expires_at)
) ENGINE=InnoDB;
CREATE TABLE upload_parts (
 upload_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 part_number INT NOT NULL, checksum_sha256 CHAR(44) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 PRIMARY KEY(upload_id,part_number), FOREIGN KEY(upload_id) REFERENCES upload_sessions(id)
) ENGINE=InnoDB;
CREATE TABLE jobs (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 video_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 media_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 type VARCHAR(24) NOT NULL DEFAULT 'TRANSCODE', state VARCHAR(24) NOT NULL,
 attempt INT NOT NULL DEFAULT 0, fence BIGINT NOT NULL DEFAULT 0,
 lease_until DATETIME(6), deadline DATETIME(6) NOT NULL,
 output_prefix VARCHAR(255), error_code VARCHAR(64), version BIGINT NOT NULL DEFAULT 1,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_job_media(media_version_id), FOREIGN KEY(tenant_id,video_id) REFERENCES videos(tenant_id,id),
 FOREIGN KEY(video_id,media_version_id) REFERENCES media_versions(video_id,id),
 INDEX ix_job_lease(state,lease_until), INDEX ix_job_video(video_id,created_at)
) ENGINE=InnoDB;
CREATE TABLE outbox_events (
 event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 aggregate_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 aggregate_version BIGINT NOT NULL, event_type VARCHAR(64) NOT NULL,
 payload JSON NOT NULL, delivery_state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 attempt INT NOT NULL DEFAULT 0, lease_until DATETIME(6), next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 occurred_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_event(aggregate_id,aggregate_version,event_type),
 INDEX ix_outbox(delivery_state,next_attempt_at,lease_until)
) ENGINE=InnoDB;
CREATE TABLE playback_progress (
 user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 video_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 media_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 position_ms BIGINT NOT NULL, updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(user_id,video_id,media_version_id), FOREIGN KEY(user_id) REFERENCES users(id),
 FOREIGN KEY(video_id,media_version_id) REFERENCES media_versions(video_id,id)
) ENGINE=InnoDB;
