-- Stands in for the OMS `orders` table the Lister reads its worklist from.
-- Mirrors the real shape: an integer serial primary key (OMS uses IntIdTable),
-- `order_id` holding the CRN as text, and a nullable invoice_pdf_uri varchar(255).
-- The PoC pages on `id` exactly as the LLD specifies, so this column set matters.
CREATE TABLE oms_orders (
    id               SERIAL PRIMARY KEY,
    order_id         VARCHAR(255) NOT NULL,
    invoice_pdf_uri  VARCHAR(255),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Deliberately NOT indexed on created_at: the LLD assumes no such index exists
-- and resolves day boundaries by binary-searching the primary key instead.
CREATE INDEX idx_oms_orders_order_id ON oms_orders (order_id);
