CREATE TABLE products (
    id    BIGSERIAL      PRIMARY KEY,
    name  VARCHAR(200)   NOT NULL,
    price NUMERIC(12, 2) NOT NULL CHECK (price >= 0),
    stock INTEGER        NOT NULL CHECK (stock >= 0)
);

INSERT INTO products (name, price, stock) VALUES
    ('Wireless Headphones', 1499.90, 100),
    ('Mechanical Keyboard', 2299.00, 50);