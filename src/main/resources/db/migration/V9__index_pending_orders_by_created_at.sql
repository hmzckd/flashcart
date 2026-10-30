CREATE INDEX orders_pending_by_created_at_idx ON orders (created_at)
    WHERE status = 'PENDING';
