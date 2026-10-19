CREATE TABLE outbox_messages (
    id              BIGSERIAL   PRIMARY KEY,
    type            VARCHAR(50) NOT NULL,
    payload         JSONB       NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    attempts        INTEGER     NOT NULL DEFAULT 0,
    sent_at         TIMESTAMPTZ,
    failed_at       TIMESTAMPTZ
);

CREATE INDEX outbox_messages_due_idx ON outbox_messages (id)
    WHERE sent_at IS NULL AND failed_at IS NULL;

ALTER TABLE refunds DROP CONSTRAINT refunds_status_check;
ALTER TABLE refunds ADD CONSTRAINT refunds_status_check
    CHECK (status IN ('REQUESTED', 'SENT', 'FAILED'));

INSERT INTO outbox_messages (type, payload, created_at, next_attempt_at)
SELECT 'RefundRequested',
       jsonb_build_object('refundId', id, 'orderId', order_id, 'amount', amount),
       now(),
       now()
FROM refunds
WHERE status = 'REQUESTED'
ORDER BY id;
