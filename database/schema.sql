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

CREATE TABLE notes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    extracted_text MEDIUMTEXT NOT NULL,
    UNIQUE KEY notes_owner (id, user_id),
    FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE quizzes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    note_id BIGINT NOT NULL,
    status ENUM('generating','ready','failed') NOT NULL,
    requested_count INT NOT NULL CHECK (requested_count BETWEEN 15 AND 20),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    failure_reason VARCHAR(255),
    UNIQUE KEY quizzes_owner (id, user_id),
    FOREIGN KEY (user_id) REFERENCES users(id),
    FOREIGN KEY (note_id, user_id) REFERENCES notes(id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE quiz_questions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    quiz_id BIGINT NOT NULL,
    position INT NOT NULL,
    question_text TEXT NOT NULL,
    explanation TEXT NOT NULL,
    correct_index INT NOT NULL CHECK (correct_index BETWEEN 0 AND 3),
    UNIQUE KEY quiz_question_position (quiz_id, position),
    UNIQUE KEY question_quiz (id, quiz_id),
    FOREIGN KEY (quiz_id) REFERENCES quizzes(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE quiz_options (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    question_id BIGINT NOT NULL,
    position INT NOT NULL CHECK (position BETWEEN 0 AND 3),
    option_text TEXT NOT NULL,
    UNIQUE KEY quiz_option_position (question_id, position),
    FOREIGN KEY (question_id) REFERENCES quiz_questions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE quiz_attempts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    quiz_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    chosen_index INT NOT NULL CHECK (chosen_index BETWEEN 0 AND 3),
    is_correct BOOLEAN NOT NULL,
    answered_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY quiz_attempt_once (quiz_id, user_id, question_id),
    FOREIGN KEY (quiz_id, user_id) REFERENCES quizzes(id, user_id),
    FOREIGN KEY (question_id, quiz_id) REFERENCES quiz_questions(id, quiz_id)
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
