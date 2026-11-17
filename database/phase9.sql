ALTER TABLE users
    ADD COLUMN role ENUM('user','admin') NOT NULL DEFAULT 'user',
    ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE exams
    ADD COLUMN semester TINYINT NOT NULL DEFAULT 0 CHECK (semester BETWEEN 0 AND 12),
    ADD COLUMN course_name VARCHAR(255) NOT NULL DEFAULT '',
    ADD COLUMN assessment ENUM('CT','MID','FINAL','OTHER') NOT NULL DEFAULT 'OTHER';

CREATE TABLE password_reset_requests (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    requested_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status ENUM('pending','issued','used','cancelled') NOT NULL DEFAULT 'pending',
    token_hash BINARY(32) UNIQUE,
    expires_at TIMESTAMP NULL,
    CONSTRAINT fk_reset_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_reset_user (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE admin_audit (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    actor_id BIGINT NOT NULL,
    target_id BIGINT NOT NULL,
    action VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_audit_actor FOREIGN KEY (actor_id) REFERENCES users(id),
    CONSTRAINT fk_audit_target FOREIGN KEY (target_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
