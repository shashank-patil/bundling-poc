-- One row per tar. Small table: ~30 rows a day, ~11,000 a year.
-- Not partitioned - partitioning it would be ceremony.
CREATE TABLE invoice_archive_bundles (
    id                BIGSERIAL PRIMARY KEY,
    bundle_bucket     TEXT        NOT NULL,
    bundle_object     TEXT        NOT NULL,
    generation        BIGINT,
    cohort_date       DATE        NOT NULL,
    state             VARCHAR(16) NOT NULL,
    layout_version    INT         NOT NULL DEFAULT 1,
    member_count      BIGINT,
    byte_size         BIGINT,
    md5               TEXT,
    manifest_offset   BIGINT,
    manifest_length   BIGINT,
    claimed_at        TIMESTAMPTZ,
    claimed_by        TEXT,
    attempts          INT         NOT NULL DEFAULT 0,
    verified_at       TIMESTAMPTZ,
    last_error        TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_bundle_object UNIQUE (bundle_bucket, bundle_object),
    CONSTRAINT ck_bundle_state  CHECK (state IN ('BUILDING', 'WRITTEN', 'VERIFIED', 'FAILED'))
);

-- A bundle has no PENDING state: it is created already BUILDING. Only a file ever waits.
CREATE INDEX idx_bundles_claimable ON invoice_archive_bundles (state, claimed_at)
    WHERE state IN ('BUILDING', 'WRITTEN');
