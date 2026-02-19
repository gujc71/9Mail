-- ============================================================
-- 9Mail Database Schema (SQLite)
-- ============================================================

-- User account information
CREATE TABLE IF NOT EXISTS MAIL_USER (
    username    TEXT        NOT NULL,
    email       TEXT        PRIMARY KEY,
    password    TEXT        NOT NULL,  -- SHA-256 hash
    created_dt  TEXT        DEFAULT (datetime('now')),
    active      INTEGER     DEFAULT 1
);

-- Mailbox information per account
CREATE TABLE IF NOT EXISTS MAIL_MAILBOX (
    mailbox_id      TEXT        PRIMARY KEY,  -- Random unique value (less than 10 digits)
    email           TEXT        NOT NULL,
    mailbox_name    TEXT        NOT NULL,
    mailbox_path    TEXT        NOT NULL,  -- Hierarchy joined by '.'
    reg_dt          TEXT        DEFAULT (datetime('now')),
    total_size      INTEGER     DEFAULT 0,
    mail_count      INTEGER     DEFAULT 0,
    next_uid        INTEGER     DEFAULT 1,
    uid_validity    INTEGER     DEFAULT 1,
    FOREIGN KEY (email) REFERENCES MAIL_USER(email) ON DELETE CASCADE
);

-- Message primary information
CREATE TABLE IF NOT EXISTS MAIL_MESSAGE (
    message_id  TEXT        PRIMARY KEY,
    subject     TEXT,
    sender      TEXT        NOT NULL,
    send_dt     TEXT,
    recipient   TEXT        NOT NULL,  -- First recipient
    filename    TEXT                  -- EML file relative path
);

-- Recipient list per message (deduplicated)
CREATE TABLE IF NOT EXISTS MAIL_RECIPIENT (
    id          INTEGER     PRIMARY KEY AUTOINCREMENT,
    message_id  TEXT        NOT NULL,
    email       TEXT        NOT NULL,
    UNIQUE(message_id, email),
    FOREIGN KEY (message_id) REFERENCES MAIL_MESSAGE(message_id) ON DELETE CASCADE
);

-- Received mail entries
CREATE TABLE IF NOT EXISTS MAIL_MAIL (
    id              INTEGER     PRIMARY KEY AUTOINCREMENT,
    message_id      TEXT        NOT NULL,
    mailbox_id      TEXT        NOT NULL,
    uid             INTEGER     NOT NULL,
    receive_dt      TEXT        DEFAULT (date('now')),
    receive_time    TEXT        DEFAULT (time('now')),
    is_read         INTEGER     DEFAULT 0,
    is_flagged      INTEGER     DEFAULT 0,
    is_answered     INTEGER     DEFAULT 0,
    is_deleted      INTEGER     DEFAULT 0,
    is_draft        INTEGER     DEFAULT 0,
    size            INTEGER     DEFAULT 0,
    FOREIGN KEY (message_id) REFERENCES MAIL_MESSAGE(message_id) ON DELETE CASCADE,
    FOREIGN KEY (mailbox_id) REFERENCES MAIL_MAILBOX(mailbox_id) ON DELETE CASCADE
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_mailbox_email ON MAIL_MAILBOX(email);
CREATE INDEX IF NOT EXISTS idx_mailbox_path ON MAIL_MAILBOX(email, mailbox_path);
CREATE INDEX IF NOT EXISTS idx_mail_mailbox ON MAIL_MAIL(mailbox_id);
CREATE INDEX IF NOT EXISTS idx_mail_message ON MAIL_MAIL(message_id);
CREATE INDEX IF NOT EXISTS idx_mail_uid ON MAIL_MAIL(mailbox_id, uid);
CREATE INDEX IF NOT EXISTS idx_recipient_message ON MAIL_RECIPIENT(message_id);
CREATE INDEX IF NOT EXISTS idx_recipient_email ON MAIL_RECIPIENT(email);
CREATE INDEX IF NOT EXISTS idx_message_sender ON MAIL_MESSAGE(sender);
