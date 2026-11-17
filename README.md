# bundling-poc

A local, runnable proof of concept for the invoice archival design in
**LLD: Invoice Archival — Bundling & Retrieval**.

It proves the three things the design actually rests on:

1. the tar offset arithmetic survives a real multi-hundred-MB bundle,
2. a member row is written **once**, with its position computed before any byte is read,
3. one document can be pulled back out of a large bundle by byte range, and verified.

## What stands in for what

| Production | Here |
| --- | --- |
| GCS source bucket | a local folder behind the same `ObjectStore` port |
| GCS archive bucket | a local folder; ranged reads via `seek`, create-only writes via `CREATE_NEW` |
| OMS `orders` | an `oms_orders` table with the same shape and an integer serial PK |
| pg_partman | 24 monthly partitions created up front |
| Snowflake CDC | out of scope, stubbed — noted where it would sit |

Bundles and generated invoices are written under `var/` and are **not** in git history.

## Running

```bash
docker-compose up -d          # postgres on :55432
./gradlew run --args="migrate"
```

See `docs/RUNBOOK.md` for the full demo sequence.
