CREATE TABLE notifications (
    id          BIGSERIAL    PRIMARY KEY,
    event_id    UUID         NOT NULL UNIQUE,
    order_id    BIGINT       NOT NULL,
    type        VARCHAR(50)  NOT NULL,
    text        VARCHAR(255) NOT NULL,
    occurred_at TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX notifications_order_id_idx ON notifications (order_id);
