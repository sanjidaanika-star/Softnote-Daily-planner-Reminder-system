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
