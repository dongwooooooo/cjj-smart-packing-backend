-- 원장 기반 재고 (docs/superpowers/specs/2026-09-23-ledger-stock-design.md).
-- stock_balance 는 inventory_tx 를 last_tx_id 까지 집계한 스냅샷이다. 실재고는
-- 스냅샷 + (id > last_tx_id 인 원장 합) 으로 읽는다. product.stock_qty 는 남기되 쓰지 않는다.

CREATE TABLE stock_balance (
    product_id  BIGINT    PRIMARY KEY REFERENCES product (id),
    qty         INT       NOT NULL,
    last_tx_id  BIGINT    NOT NULL,
    computed_at TIMESTAMP NOT NULL DEFAULT now()
);

INSERT INTO stock_balance (product_id, qty, last_tx_id)
SELECT p.id, COALESCE(SUM(t.qty_delta), 0), COALESCE(MAX(t.id), 0)
FROM product p LEFT JOIN inventory_tx t ON t.product_id = p.id
GROUP BY p.id;

DROP INDEX IF EXISTS ix_inventory_tx_product;
CREATE INDEX ix_inventory_tx_product_id ON inventory_tx (product_id, id);

ALTER TABLE inventory_tx ADD COLUMN idempotency_key VARCHAR(80);
ALTER TABLE inventory_tx ADD COLUMN reason VARCHAR(200);
CREATE UNIQUE INDEX ux_inventory_tx_idem ON inventory_tx (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE VIEW v_stock_on_hand AS
SELECT b.product_id,
       b.qty + COALESCE((SELECT SUM(t.qty_delta) FROM inventory_tx t
                         WHERE t.product_id = b.product_id AND t.id > b.last_tx_id), 0) AS on_hand_qty,
       b.last_tx_id,
       b.computed_at
FROM stock_balance b;
