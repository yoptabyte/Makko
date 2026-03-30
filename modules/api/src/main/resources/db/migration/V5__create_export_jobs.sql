CREATE TABLE export_jobs (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  link_id BIGINT NOT NULL,
  status ENUM('pending','exporting','done','failed') NOT NULL DEFAULT 'pending',
  vault_path VARCHAR(500) NULL,
  error_msg TEXT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at TIMESTAMP NULL,
  UNIQUE INDEX uk_export_jobs_link_id (link_id),
  INDEX idx_status (status),
  CONSTRAINT fk_ej_link FOREIGN KEY (link_id) REFERENCES links(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
