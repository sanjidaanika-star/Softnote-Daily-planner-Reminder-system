CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARBINARY(255) NOT NULL,
    password_salt VARBINARY(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE exams (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    exam_date DATE NOT NULL,
    daily_minutes INT NOT NULL CHECK (daily_minutes > 0),
    CONSTRAINT fk_exams_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE chapters (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    exam_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    difficulty ENUM('easy', 'medium', 'hard') NOT NULL,
    estimated_minutes INT NOT NULL CHECK (estimated_minutes > 0),
    UNIQUE KEY uq_chapters_exam_id (exam_id, id),
    CONSTRAINT fk_chapters_exam FOREIGN KEY (exam_id) REFERENCES exams (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE study_sessions (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    exam_id BIGINT NOT NULL,
    chapter_id BIGINT NOT NULL,
    session_date DATE NOT NULL,
    planned_minutes INT NOT NULL CHECK (planned_minutes > 0),
    status ENUM('planned', 'completed', 'missed') NOT NULL DEFAULT 'planned',
    is_revision BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_study_sessions_exam FOREIGN KEY (exam_id) REFERENCES exams (id),
    CONSTRAINT fk_study_sessions_chapter FOREIGN KEY (exam_id, chapter_id)
        REFERENCES chapters (exam_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE unscheduled_sessions (
    session_id BIGINT NOT NULL PRIMARY KEY,
    minutes INT NOT NULL CHECK (minutes > 0),
    CONSTRAINT fk_unscheduled_session FOREIGN KEY (session_id) REFERENCES study_sessions (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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

-- Apply once to an existing Softnote database before deploying reminders.
CREATE TABLE IF NOT EXISTS reminder_settings (
    user_id BIGINT PRIMARY KEY,
    daily_time TIME NOT NULL DEFAULT '20:00:00',
    time_zone VARCHAR(80) NOT NULL DEFAULT 'Asia/Dhaka',
    daily_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    exam_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    email_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    push_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS push_subscriptions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    endpoint_hash CHAR(64) NOT NULL UNIQUE,
    endpoint VARCHAR(2048) NOT NULL,
    public_key VARCHAR(100) NOT NULL,
    auth_secret VARCHAR(32) NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS reminders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    event_key VARCHAR(100) NOT NULL,
    title VARCHAR(255) NOT NULL,
    message TEXT NOT NULL,
    target_path VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE KEY reminder_event (user_id, event_key),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS reminder_deliveries (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    reminder_id BIGINT NOT NULL,
    channel ENUM('email','push') NOT NULL,
    target_id BIGINT NOT NULL DEFAULT 0,
    status ENUM('pending','sending','sent','failed','cancelled') NOT NULL DEFAULT 'pending',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    failure_reason VARCHAR(255),
    UNIQUE KEY reminder_target (reminder_id, channel, target_id),
    INDEX due_deliveries (status, next_attempt),
    FOREIGN KEY (reminder_id) REFERENCES reminders(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Apply once before deploying online exams. Existing personal study plans are unchanged.
CREATE TABLE IF NOT EXISTS online_exams (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    created_by BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    course_name VARCHAR(255) NOT NULL,
    semester TINYINT NOT NULL CHECK (semester BETWEEN 0 AND 12),
    opens_at BIGINT NOT NULL,
    closes_at BIGINT NOT NULL,
    duration_minutes INT NOT NULL CHECK (duration_minutes BETWEEN 1 AND 240),
    questions_json MEDIUMTEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (created_by) REFERENCES users(id),
    CHECK (closes_at > opens_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS online_exam_attempts (
    exam_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    started_at BIGINT NULL,
    deadline BIGINT NULL,
    submitted_at BIGINT NULL,
    answers_json TEXT NOT NULL,
    score INT NULL,
    PRIMARY KEY (exam_id, user_id),
    FOREIGN KEY (exam_id) REFERENCES online_exams(id),
    FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX exam_deadlines (submitted_at, deadline)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
