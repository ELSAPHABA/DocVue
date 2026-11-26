CREATE TABLE videos (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_by CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 title VARCHAR(120) NOT NULL, description VARCHAR(4000) NOT NULL,
 visibility VARCHAR(16) NOT NULL, difficulty VARCHAR(16) NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
 duration_ms BIGINT, preview_enabled BOOLEAN NOT NULL DEFAULT FALSE,
 catalog_at DATETIME(6), created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 timeline_revision BIGINT NOT NULL DEFAULT 0, version BIGINT NOT NULL DEFAULT 0,
 UNIQUE KEY uq_video_tenant(tenant_id,id),
 FOREIGN KEY(tenant_id) REFERENCES tenants(id), FOREIGN KEY(created_by) REFERENCES users(id),
 INDEX ix_video_catalog(visibility,preview_enabled,status,catalog_at DESC,id DESC),
 INDEX ix_video_tenant(tenant_id,status,updated_at DESC,id DESC),
 INDEX ix_video_owner(tenant_id,created_by,status,updated_at DESC,id DESC),
 CHECK(duration_ms IS NULL OR duration_ms BETWEEN 1 AND 14400000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE video_repositories (
 video_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 registry_name VARCHAR(180) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 status VARCHAR(24) NOT NULL DEFAULT 'RESERVED',
 FOREIGN KEY(tenant_id,video_id) REFERENCES videos(tenant_id,id)
) ENGINE=InnoDB;
CREATE TABLE video_labels (
 video_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 kind VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 label VARCHAR(40) NOT NULL,
 PRIMARY KEY(video_id,kind,label), INDEX ix_label(kind,label,video_id),
 FOREIGN KEY(video_id) REFERENCES videos(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
CREATE TABLE phases (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 video_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 ordinal INT NOT NULL, start_ms BIGINT NOT NULL, end_ms BIGINT NOT NULL,
 title VARCHAR(120) NOT NULL, objective VARCHAR(2000) NOT NULL,
 prerequisites VARCHAR(1000) NOT NULL, estimated_minutes INT NOT NULL,
 start_state VARCHAR(1000) NOT NULL, completion_criteria VARCHAR(1000) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0,
 UNIQUE KEY uq_phase_ordinal(video_id,ordinal), INDEX ix_phase_time(video_id,start_ms),
 FOREIGN KEY(tenant_id,video_id) REFERENCES videos(tenant_id,id),
 CHECK(start_ms>=0 AND end_ms>start_ms AND end_ms<=14400000),
 CHECK(ordinal BETWEEN 1 AND 40), CHECK(estimated_minutes BETWEEN 1 AND 1440)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Preview-only learning intent, distinct from future release-bound playback and verification.
CREATE TABLE preview_progress (
 user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 video_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 phase_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 video_version BIGINT NOT NULL, updated_at DATETIME(6) NOT NULL,
 PRIMARY KEY(user_id,video_id),
 FOREIGN KEY(user_id) REFERENCES users(id), FOREIGN KEY(video_id) REFERENCES videos(id)
) ENGINE=InnoDB;
