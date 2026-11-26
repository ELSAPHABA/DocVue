CREATE TABLE users (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 normalized_email VARCHAR(254) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 password_hash VARCHAR(255) NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
 email_verified_at DATETIME(6),
 creator_approved BOOLEAN NOT NULL DEFAULT FALSE,
 platform_role VARCHAR(24) NOT NULL DEFAULT 'USER',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB;
CREATE TABLE tenants (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 slug VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 name VARCHAR(120) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
 quota_json JSON NOT NULL, version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB;
CREATE TABLE memberships (
 tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 role VARCHAR(24) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
 version BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(tenant_id,user_id), FOREIGN KEY(tenant_id) REFERENCES tenants(id),
 FOREIGN KEY(user_id) REFERENCES users(id), INDEX ix_membership_user(user_id,status,tenant_id)
) ENGINE=InnoDB;
CREATE TABLE email_verifications (
 token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 expires_at DATETIME(6) NOT NULL, consumed_at DATETIME(6),
 FOREIGN KEY(user_id) REFERENCES users(id), INDEX ix_verify_user(user_id,consumed_at)
) ENGINE=InnoDB;
CREATE TABLE refresh_families (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 expires_at DATETIME(6) NOT NULL, revoked_at DATETIME(6),
 FOREIGN KEY(user_id) REFERENCES users(id), INDEX ix_family_user(user_id)
) ENGINE=InnoDB;
CREATE TABLE refresh_tokens (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 family_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 expires_at DATETIME(6) NOT NULL, used_at DATETIME(6), revoked_at DATETIME(6),
 replaced_by CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
 FOREIGN KEY(family_id) REFERENCES refresh_families(id), FOREIGN KEY(user_id) REFERENCES users(id),
 INDEX ix_refresh_family(family_id,revoked_at)
) ENGINE=InnoDB;
CREATE TABLE invitations (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 email VARCHAR(254) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 role VARCHAR(24) NOT NULL, expires_at DATETIME(6) NOT NULL, consumed_at DATETIME(6),
 FOREIGN KEY(tenant_id) REFERENCES tenants(id), INDEX ix_invitation(tenant_id,email,consumed_at)
) ENGINE=InnoDB;
CREATE TABLE idempotency_keys (
 actor_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 tenant_scope VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 route VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 state VARCHAR(20) NOT NULL, response_json JSON NOT NULL, expires_at DATETIME(6) NOT NULL,
 PRIMARY KEY(actor_id,tenant_scope,route,request_key), INDEX ix_idempotency_expiry(expires_at)
) ENGINE=InnoDB;
CREATE TABLE audit_events (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
 actor_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
 action VARCHAR(64) NOT NULL, target_type VARCHAR(32) NOT NULL,
 target_id VARCHAR(36), trace_id VARCHAR(36) NOT NULL,
 occurred_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), sanitized_details JSON NOT NULL,
 INDEX ix_audit_tenant(tenant_id,occurred_at,id), INDEX ix_audit_target(target_type,target_id,occurred_at)
) ENGINE=InnoDB;
