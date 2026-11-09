CREATE TABLE conversation_labels (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(30) NOT NULL,
    color_id VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_conversation_labels_user_id_id UNIQUE (user_id, id),
    CONSTRAINT ck_conversation_labels_name CHECK (
        name = btrim(name) AND char_length(name) BETWEEN 1 AND 30
    ),
    CONSTRAINT ck_conversation_labels_color CHECK (
        color_id IN ('INDIGO', 'BLUE', 'CYAN', 'TEAL', 'EMERALD', 'AMBER', 'ROSE', 'PURPLE')
    )
);

CREATE UNIQUE INDEX uq_conversation_labels_user_name_lower
    ON conversation_labels (user_id, lower(name));

CREATE TABLE conversation_label_assignments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL,
    user_id UUID NOT NULL,
    label_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_conversation_label_assignments_member_label
        UNIQUE (conversation_id, user_id, label_id),
    CONSTRAINT fk_conversation_label_assignments_member
        FOREIGN KEY (conversation_id, user_id)
        REFERENCES conversation_members (conversation_id, user_id) ON DELETE CASCADE,
    CONSTRAINT fk_conversation_label_assignments_label
        FOREIGN KEY (user_id, label_id)
        REFERENCES conversation_labels (user_id, id) ON DELETE CASCADE
);

CREATE INDEX idx_conversation_label_assignments_user_label_conversation
    ON conversation_label_assignments (user_id, label_id, conversation_id);
