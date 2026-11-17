-- One row per file. This is the table that matters: after the delete phase an
-- invoice is a byte range reachable only through its member row.
--
-- Partitioned by cohort_date, monthly. One cron run touches exactly one
-- partition, so no cross-partition write ever happens.
--
-- The unique key MUST lead with cohort_date: Postgres requires the partition key
-- in every unique constraint on a partitioned table. Without it this CREATE fails.
CREATE TABLE invoice_archive_members (
    cohort_date        DATE        NOT NULL,
    source_object      TEXT        NOT NULL,
    source_generation  BIGINT      NOT NULL,
    crn                TEXT        NOT NULL,
    doc_kind           VARCHAR(16) NOT NULL,
    doc_format         VARCHAR(8)  NOT NULL,
    bundle_id          BIGINT      NOT NULL,
    offset_bytes       BIGINT      NOT NULL,
    length_bytes       BIGINT      NOT NULL,
    crc32c             TEXT        NOT NULL,
    attempts           INT         NOT NULL DEFAULT 0,
    last_error         TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (cohort_date, source_object, source_generation)
) PARTITION BY RANGE (cohort_date);

-- Retrieval index. The CRN is parsed from the object name, never read from
-- invoices.pdf_uri. Declared on the parent so every partition inherits it.
CREATE INDEX idx_members_retrieval ON invoice_archive_members (crn, doc_kind, doc_format);

-- Lets the Bundler find a bundle's members in write order without a sort.
CREATE INDEX idx_members_by_bundle ON invoice_archive_members (bundle_id, offset_bytes);

-- Production uses pg_partman for premake and retention. The PoC creates a fixed
-- 24-month window up front, which exercises the same constraint without the
-- extension - the LLD's live window is exactly 24 partitions.
DO $$
DECLARE
    start_month DATE := date_trunc('month', now())::date - INTERVAL '18 months';
    m           DATE;
BEGIN
    FOR i IN 0..23 LOOP
        m := (start_month + (i || ' months')::INTERVAL)::date;
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF invoice_archive_members FOR VALUES FROM (%L) TO (%L)',
            'invoice_archive_members_' || to_char(m, 'YYYY_MM'),
            m,
            (m + INTERVAL '1 month')::date
        );
    END LOOP;
END $$;
