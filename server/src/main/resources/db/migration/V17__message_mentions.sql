ALTER TABLE messages
    ADD COLUMN mention_all BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT uq_messages_id_conversation UNIQUE (id, conversation_id);

CREATE TABLE message_mentions (
    message_id UUID NOT NULL,
    conversation_id UUID NOT NULL,
    user_id UUID NOT NULL,
    mention_offset INTEGER NOT NULL CHECK (mention_offset >= 0),
    mention_length INTEGER NOT NULL CHECK (mention_length > 0),
    PRIMARY KEY (message_id, user_id),
    CONSTRAINT chk_message_mentions_range
        CHECK (mention_offset::bigint + mention_length::bigint <= 10000),
    CONSTRAINT fk_message_mentions_message
        FOREIGN KEY (message_id, conversation_id)
        REFERENCES messages (id, conversation_id) ON DELETE CASCADE,
    CONSTRAINT fk_message_mentions_member
        FOREIGN KEY (conversation_id, user_id)
        REFERENCES conversation_members (conversation_id, user_id) ON DELETE CASCADE
);

CREATE INDEX idx_message_mentions_conversation_user
    ON message_mentions (conversation_id, user_id);
