-- Legacy reference only. Runtime initialization is disabled; use Flyway V0-V3.
CREATE TABLE IF NOT EXISTS app_users (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'STUDENT',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_users_role CHECK (role IN ('STUDENT', 'ADMIN'))
);

ALTER TABLE app_users ADD COLUMN IF NOT EXISTS role VARCHAR(20) NOT NULL DEFAULT 'STUDENT';

CREATE INDEX IF NOT EXISTS idx_users_email ON app_users(email);

