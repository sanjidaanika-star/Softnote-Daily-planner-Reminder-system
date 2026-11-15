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
