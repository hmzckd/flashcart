ALTER TABLE outbox_messages ADD COLUMN message_key VARCHAR(100);

UPDATE outbox_messages
SET message_key = 'refund-' || (payload ->> 'refundId')
WHERE type = 'RefundRequested';

ALTER TABLE outbox_messages ALTER COLUMN message_key SET NOT NULL;

CREATE INDEX outbox_messages_unsent_by_key_idx ON outbox_messages (message_key, id)
    WHERE sent_at IS NULL AND failed_at IS NULL;
