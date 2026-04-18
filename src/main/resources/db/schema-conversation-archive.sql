ALTER TABLE vector_store_conversation
ADD COLUMN IF NOT EXISTS conversation_id VARCHAR(128);

ALTER TABLE vector_store_conversation
ADD COLUMN IF NOT EXISTS memory_type VARCHAR(32) NOT NULL DEFAULT 'MESSAGE';

ALTER TABLE vector_store_conversation
ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

ALTER TABLE vector_store_conversation
ADD COLUMN IF NOT EXISTS turn_index INTEGER;

ALTER TABLE vector_store_conversation
ADD COLUMN IF NOT EXISTS message_index INTEGER;

CREATE INDEX IF NOT EXISTS idx_vector_store_conversation_session
ON vector_store_conversation(conversation_id);

CREATE INDEX IF NOT EXISTS idx_vector_store_conversation_memory_type
ON vector_store_conversation(memory_type);

CREATE UNIQUE INDEX IF NOT EXISTS uq_vector_store_conversation_turn_message
ON vector_store_conversation(conversation_id, turn_index, message_index)
WHERE turn_index IS NOT NULL AND message_index IS NOT NULL;
