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
