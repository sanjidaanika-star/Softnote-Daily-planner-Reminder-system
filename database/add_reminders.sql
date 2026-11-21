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
