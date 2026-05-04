-- Add 'CALL' to the message type constraint
ALTER TABLE conversation_message
    DROP CONSTRAINT IF EXISTS conversation_message_type_check;

ALTER TABLE conversation_message
    ADD CONSTRAINT conversation_message_type_check
        CHECK (type IN ('TEXT', 'FILE', 'IMAGE', 'VIDEO', 'CALL'));
