CREATE TABLE refunds (
    id         BIGSERIAL      PRIMARY KEY,
    order_id   BIGINT         NOT NULL REFERENCES orders (id),
    amount     NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    reason     VARCHAR(30)    NOT NULL
        CHECK (reason IN ('ORDER_CANCELLED', 'ORDER_NOT_PENDING', 'AMOUNT_MISMATCH')),
    status     VARCHAR(20)    NOT NULL CHECK (status IN ('REQUESTED')),
    created_at TIMESTAMPTZ    NOT NULL
);

CREATE TABLE payment_events (
    id          VARCHAR(100)   PRIMARY KEY,
    type        VARCHAR(50)    NOT NULL,
    order_id    BIGINT,
    amount      NUMERIC(12, 2),
    received_at TIMESTAMPTZ    NOT NULL DEFAULT now()
);
