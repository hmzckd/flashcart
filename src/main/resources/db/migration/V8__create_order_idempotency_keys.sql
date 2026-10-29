CREATE TABLE order_idempotency_keys (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    order_id        BIGINT       REFERENCES orders (id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
