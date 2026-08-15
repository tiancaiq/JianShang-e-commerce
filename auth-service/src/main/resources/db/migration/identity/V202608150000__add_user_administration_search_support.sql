ALTER TABLE users
    ADD COLUMN account_type VARCHAR(32) NOT NULL DEFAULT 'HUMAN' AFTER status,
    ADD CONSTRAINT chk_users_account_type CHECK (account_type IN ('HUMAN', 'SERVICE', 'AUTOMATION'));

CREATE INDEX idx_users_created_id ON users (created_at, id);
CREATE INDEX idx_users_updated_id ON users (updated_at, id);
