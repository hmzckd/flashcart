CREATE TABLE products (                                  -- products adında tablo oluştur
    id    BIGSERIAL      PRIMARY KEY,                     -- Otomatik artan numara (1, 2, 3...), her ürünün kimliği
    name  VARCHAR(200)   NOT NULL,                        -- Ürün adı, en fazla 200 karakter, boş olamaz
    price NUMERIC(12, 2) NOT NULL CHECK (price >= 0),     -- Fiyat: virgülden sonra tam 2 hane, eksi olamaz
    stock INTEGER        NOT NULL CHECK (stock >= 0)      -- Stok adedi, eksi olamaz (tahmin sorusunun cevabı)
);

INSERT INTO products (name, price, stock) VALUES         -- Denemek için 2 örnek ürün ekle
    ('Wireless Headphones', 1499.90, 100),
    ('Mechanical Keyboard', 2299.00, 50);