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
