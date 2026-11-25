CREATE TABLE platform_schema_metadata (
  id TINYINT NOT NULL PRIMARY KEY,
  schema_label VARCHAR(32) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO platform_schema_metadata (id, schema_label) VALUES (1, 'phase-01');
