CREATE TABLE unscheduled_sessions (
    session_id BIGINT NOT NULL PRIMARY KEY,
    minutes INT NOT NULL CHECK (minutes > 0),
    CONSTRAINT fk_unscheduled_session FOREIGN KEY (session_id) REFERENCES study_sessions (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
