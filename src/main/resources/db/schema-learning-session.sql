CREATE TABLE IF NOT EXISTS learning_session (
    id BIGSERIAL PRIMARY KEY,
    conversation_id VARCHAR(128) NOT NULL UNIQUE,
    user_id VARCHAR(128),
    subject VARCHAR(64),
    session_type VARCHAR(32) NOT NULL,
    learning_goal TEXT,
    current_topic TEXT,
    conversation_mode VARCHAR(32) NOT NULL DEFAULT 'MEMORY_ENABLED',
    turn_count INTEGER NOT NULL DEFAULT 0,
    last_summarized_turn INTEGER NOT NULL DEFAULT 0,
    processing_request_id VARCHAR(128),
    processing_lease_expires_at TIMESTAMPTZ,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_active_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE learning_session
ADD COLUMN IF NOT EXISTS last_summarized_turn INTEGER NOT NULL DEFAULT 0;

ALTER TABLE learning_session
ADD COLUMN IF NOT EXISTS processing_request_id VARCHAR(128);

ALTER TABLE learning_session
ADD COLUMN IF NOT EXISTS processing_lease_expires_at TIMESTAMPTZ;

ALTER TABLE learning_session
ADD COLUMN IF NOT EXISTS conversation_mode VARCHAR(32) NOT NULL DEFAULT 'MEMORY_ENABLED';

CREATE INDEX IF NOT EXISTS idx_learning_session_user_id
ON learning_session(user_id);

CREATE INDEX IF NOT EXISTS idx_learning_session_last_active_at
ON learning_session(last_active_at DESC);

CREATE INDEX IF NOT EXISTS idx_learning_session_processing_request
ON learning_session(processing_request_id);
