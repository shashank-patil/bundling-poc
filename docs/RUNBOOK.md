# Runbook

Everything needed to run, inspect and benchmark the PoC yourself. Copy-paste
from top to bottom and it works.

All generated data lives in `bundling-poc/var/` and is **git-ignored** — no tar
and no PDF ever enters git history.

- [0. Prerequisites](#0-prerequisites)
- [1. Full reset](#1-full-reset)
- [2. Build](#2-build)
- [3. Start Postgres and apply the schema](#3-start-postgres-and-apply-the-schema)
- [4. Generate a corpus](#4-generate-a-corpus)
- [5. Cron 1 — the Lister](#5-cron-1--the-lister)
- [6. Cron 2 — the Bundler](#6-cron-2--the-bundler)
- [7. Go inside the database](#7-go-inside-the-database)
- [8. Prove the tar independently](#8-prove-the-tar-independently)
- [9. Retrieval API](#9-retrieval-api)
- [10. Failure demo](#10-failure-demo)
- [11. Delete phase](#11-delete-phase)
- [12. Benchmarking](#12-benchmarking)
- [13. Run it on kind](#13-run-it-on-kind)
- [14. Cleanup](#14-cleanup)
- [15. Troubleshooting](#15-troubleshooting)

---

## 0. Prerequisites

```bash
java -version          # 17
docker --version       # running
docker-compose version
kubectl version --client
kind --version         # only for section 13:  brew install kind
```

```bash
cd ~/PorterWorkSpace/bundling-poc
```

**Disk:** a 5 GiB bundle needs about **11 GB** free — 5.3 GB of generated
invoices plus a 5 GiB tar. A 1 GiB bundle needs about 2.2 GB.

---

## 1. Full reset

Run this whenever you want to start clean. It deletes the database volume and
every generated file.

```bash
pkill -f "in.porter.archival.app.MainKt"   # stop any running server
docker-compose down -v                     # removes the pgdata volume - WIPES the DB
rm -rf var
mkdir -p var/sources var/archive
```

---

## 2. Build

```bash
./gradlew build                 # compiles and runs all 18 tests
./gradlew installDist           # produces a runnable script, no Gradle at run time
```

Two ways to run the app. **Use the second for anything you are timing** — the
first includes Gradle's own start-up (~4 s) in every measurement.

```bash
./gradlew run --args="<command>"                    # convenient
build/install/bundling-poc/bin/bundling-poc <command>   # accurate
```

Everything below uses `$APP`:

```bash
APP=build/install/bundling-poc/bin/bundling-poc
```

---

## 3. Start Postgres and apply the schema

```bash
docker-compose up -d
until docker exec bundling-poc-db pg_isready -U archival -d archival; do sleep 1; done

$APP migrate
```

Confirm the schema is what the LLD specifies:

```bash
docker exec -it bundling-poc-db psql -U archival -d archival -c "\d+ invoice_archive_members"
```

Look for:
- `Partition key: RANGE (cohort_date)`
- primary key `(cohort_date, source_object, source_generation)` — **partition
  column first**, which Postgres requires
- 24 monthly partitions, each inheriting `idx_members_retrieval`

---

## 4. Generate a corpus

```bash
# ~1 GiB bundle: 5,000 orders at 200 KB  (fast, good for a demo)
$APP seed --orders 5000 --size-kb 200 --cohort 2026-08-14

# ~5 GiB bundle: 26,000 orders at 200 KB  (what the benchmark used)
$APP seed --orders 26000 --size-kb 200 --cohort 2026-08-14

# real-world shape: production invoices measure ~96 KB median
$APP seed --orders 50000 --size-kb 96 --cohort 2026-08-14
```

Each order produces a **real, openable PDF** plus an HTML twin. Every 20th
order also gets a CN/DN that **no order row points at** — that is the LLD's
"object no worklist row points at" case, and it must survive untouched.

```bash
du -sh var/sources
find var/sources -name '*.pdf' ! -name '*CN_DN*' | wc -l     # invoices
find var/sources -name '*CN_DN*.pdf' | wc -l                 # unreferenced
```

> **Seeding is not idempotent.** Objects are written create-only
> (`ifGenerationMatch=0`), so re-seeding the same cohort fails by design. Use a
> different `--cohort`, or reset (section 1).

---

## 5. Cron 1 — the Lister

Reads metadata only. No payload is read and no tar is written.

```bash
$APP lister --cohort 2026-08-14

# or let it pick T-90 automatically, as production does
$APP lister
```

```bash
$APP status --cohort 2026-08-14
```

**Prove a rerun is free** — run it twice and the member count must not move:

```bash
$APP lister --cohort 2026-08-14
docker exec bundling-poc-db psql -U archival -d archival -tAc \
  "SELECT count(*) FROM invoice_archive_members"
```

---

## 6. Cron 2 — the Bundler

Frames, uploads and verifies. Prints all six checks per bundle.

```bash
$APP bundler
```

With check 6 on (reads every byte back and crc32c's it):

```bash
VERIFY_SAMPLE_RATIO=1.0 $APP bundler
```

Production configuration — the LLD's production column rather than staging:

```bash
FETCH_CONCURRENCY=32 \
QUEUE_BYTES=67108864 \
UPLOAD_CHUNK_BYTES=16777216 \
MAX_BUNDLE_MEMBERS=100000 \
TARGET_BUNDLE_BYTES=6442450944 \
  $APP bundler
```

Any key in `archival.yaml` or `engine.properties` can be overridden by an
environment variable: `cutoffDays` → `CUTOFF_DAYS`, `targetBundleBytes` →
`TARGET_BUNDLE_BYTES`, and so on.

---

## 7. Go inside the database

```bash
docker exec -it bundling-poc-db psql -U archival -d archival
```

Useful meta-commands once inside: `\dt` tables, `\d+ <table>` detail,
`\x` toggle expanded output, `\q` quit.

### Every bundle

```sql
SELECT id, cohort_date, bundle_object, state, member_count,
       pg_size_pretty(byte_size) AS size,
       manifest_offset, manifest_length, attempts, last_error
  FROM invoice_archive_bundles
 ORDER BY id;
```

### Members per cohort, and anything that failed

```sql
SELECT cohort_date, bundle_id, count(*) AS members,
       count(*) FILTER (WHERE doc_format='pdf')  AS pdfs,
       count(*) FILTER (WHERE doc_format='html') AS html,
       pg_size_pretty(sum(length_bytes)) AS payload,
       count(*) FILTER (WHERE last_error IS NOT NULL) AS failed
  FROM invoice_archive_members
 GROUP BY 1,2 ORDER BY 1,2;
```

### One document's index row — this is exactly what retrieval reads

```sql
SELECT crn, doc_kind, doc_format, bundle_id, offset_bytes, length_bytes, crc32c
  FROM invoice_archive_members
 WHERE crn = 'CRN0020497000042';
```

Pick a real CRN first:

```sql
SELECT crn FROM invoice_archive_members
 WHERE doc_format='pdf' ORDER BY random() LIMIT 5;
```

### The offset invariants — both must return 0

```sql
-- every offset sits on a 512-byte boundary
SELECT count(*) AS misaligned
  FROM invoice_archive_members WHERE offset_bytes % 512 <> 0;

-- each member ends exactly one header before the next one starts
SELECT count(*) AS bad_gaps FROM (
  SELECT bundle_id, offset_bytes, length_bytes,
         lead(offset_bytes) OVER (PARTITION BY bundle_id ORDER BY offset_bytes) AS nxt
    FROM invoice_archive_members) t
 WHERE nxt IS NOT NULL
   AND nxt <> offset_bytes + ((length_bytes + 511)/512)*512 + 512;
```

### Recompute each tar's size from its rows and compare to what was stored

```sql
SELECT b.id, b.byte_size,
       SUM(512 + ((m.length_bytes+511)/512)*512) + 512
         + ((b.manifest_length+511)/512)*512 + 1024 AS computed,
       b.byte_size = SUM(512 + ((m.length_bytes+511)/512)*512) + 512
         + ((b.manifest_length+511)/512)*512 + 1024 AS match
  FROM invoice_archive_bundles b
  JOIN invoice_archive_members m ON m.bundle_id = b.id
 WHERE b.state = 'VERIFIED'
 GROUP BY b.id, b.byte_size, b.manifest_length;
```

### Cohort purity — no member may belong to another day

```sql
SELECT m.cohort_date,
       (SELECT created_at::date FROM oms_orders o WHERE o.order_id = m.crn) AS real_order_date,
       count(*)
  FROM invoice_archive_members m
 GROUP BY 1,2 ORDER BY 1,2;
```

Two rows for one `cohort_date` means the worklist pulled in the wrong day.

### Partitioning

```sql
-- pruning: only one partition should be scanned
EXPLAIN SELECT * FROM invoice_archive_members WHERE cohort_date = '2026-08-14';

-- which partitions actually hold rows
SELECT c.relname, c.reltuples::bigint AS approx_rows
  FROM pg_class c JOIN pg_inherits i ON c.oid = i.inhrelid
 WHERE i.inhparent = 'invoice_archive_members'::regclass AND c.reltuples > 0;
```

### Index cost per member row

```sql
SELECT (SELECT count(*) FROM invoice_archive_members) AS member_rows,
       pg_size_pretty(SUM(pg_total_relation_size(c.oid))) AS total,
       pg_size_pretty(SUM(pg_relation_size(c.oid)))       AS heap,
       pg_size_pretty(SUM(pg_indexes_size(c.oid)))        AS indexes,
       ROUND(SUM(pg_total_relation_size(c.oid))::numeric
             / (SELECT count(*) FROM invoice_archive_members), 1) AS bytes_per_member
  FROM pg_class c JOIN pg_inherits i ON c.oid = i.inhrelid
 WHERE i.inhparent = 'invoice_archive_members'::regclass;
```

Measured at **475 bytes per member row**. See `docs/BENCHMARK-5GiB.md`.

### The worklist source

```sql
SELECT count(*), min(id), max(id), min(created_at)::date FROM oms_orders;
SELECT invoice_pdf_uri FROM oms_orders LIMIT 1;
```

### One-liners without entering psql

```bash
docker exec bundling-poc-db psql -U archival -d archival -c \
  "SELECT id, state, member_count FROM invoice_archive_bundles ORDER BY id"

docker exec bundling-poc-db psql -U archival -d archival -tAc \
  "SELECT count(*) FROM invoice_archive_members"
```

---

## 8. Prove the tar independently

None of our code is involved here, which is the point.

```bash
BUNDLE=$(find var/archive -name '*.tar' | head -1)
ls -lh "$BUNDLE"

tar -tvf "$BUNDLE" | head
tar -tvf "$BUNDLE" | wc -l     # member_count + 1 for MANIFEST.json
```

Extract one document two different ways and compare them:

```bash
CRN=$(docker exec bundling-poc-db psql -U archival -d archival -tAc \
  "SELECT crn FROM invoice_archive_members WHERE doc_format='pdf' LIMIT 1")

read NAME OFF LEN <<<$(docker exec bundling-poc-db psql -U archival -d archival -tAF' ' -c \
  "SELECT source_object, offset_bytes, length_bytes FROM invoice_archive_members
    WHERE crn='$CRN' AND doc_format='pdf'")

# a) through tar, by name
tar -xOf "$BUNDLE" "$NAME" > /tmp/via_tar.pdf

# b) raw bytes at the offset the database recorded - no tar involved
dd if="$BUNDLE" bs=1 skip="$OFF" count="$LEN" of=/tmp/via_offset.pdf status=none

cmp /tmp/via_tar.pdf /tmp/via_offset.pdf && echo "IDENTICAL - the offset is correct"
file /tmp/via_offset.pdf        # PDF document, version 1.4
open /tmp/via_offset.pdf        # it really opens
```

---

## 9. Retrieval API

```bash
$APP serve                       # foreground, port 8080
$APP serve --port 9090           # different port
```

In another shell:

```bash
curl -s http://localhost:8080/health

CRN=$(docker exec bundling-poc-db psql -U archival -d archival -tAc \
  "SELECT crn FROM invoice_archive_members WHERE doc_format='pdf' ORDER BY random() LIMIT 1")

# the PDF
curl -s -D- -o /tmp/out.pdf \
  "http://localhost:8080/documents/$CRN?kind=INVOICE&format=pdf"

file /tmp/out.pdf
open /tmp/out.pdf
```

`X-Archive-Source` in the response names the tar, the offset and the length the
bytes came from — e.g. `2026/08/14/bundle-00000.tar@1103072768+204726`.

### The other cases

```bash
# the HTML twin                                    -> 200
curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:8080/documents/$CRN?kind=INVOICE&format=html"

# a CRN that was never archived                    -> 404
curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:8080/documents/CRN0000000000000?kind=INVOICE&format=pdf"

# an unreferenced CN/DN - never archived            -> 404
curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:8080/documents/$CRN?kind=CN_DN&format=pdf"
```

A bundle that is not yet `VERIFIED` returns **409**; a checksum mismatch
returns **502** — never a wrong document.

### Latency

```bash
for C in $(docker exec bundling-poc-db psql -U archival -d archival -tAc \
    "SELECT crn FROM invoice_archive_members WHERE doc_format='pdf' ORDER BY random() LIMIT 30"); do
  curl -s -o /dev/null -w "%{time_total}\n" "http://localhost:8080/documents/$C?kind=INVOICE&format=pdf"
done | sort -n | awk '{a[NR]=$1} END {printf "min %.1f ms  median %.1f ms  max %.1f ms\n", a[1]*1000, a[int(NR/2)]*1000, a[NR]*1000}'
```

Measured on a 5 GiB tar: **median 6 ms, p95 10 ms**. Bundle size does not
affect it.

Stop the server:

```bash
pkill -f "in.porter.archival.app.MainKt"
```

---

## 10. Failure demo

Shows that a member which cannot be read fails the **whole** bundle and the
survivors are re-planned — never written with a gap, never written short.

```bash
$APP seed   --orders 300 --size-kb 200 --cohort 2026-08-15
$APP lister --cohort 2026-08-15
$APP demo-corrupt --cohort 2026-08-15        # truncates one source to 16 bytes
$APP bundler
```

```sql
SELECT id, state, attempts, last_error
  FROM invoice_archive_bundles WHERE cohort_date = '2026-08-15';

SELECT source_object, attempts, last_error
  FROM invoice_archive_members
 WHERE cohort_date = '2026-08-15' AND last_error IS NOT NULL;
```

Expect the first bundle `FAILED`, a second bundle holding the survivors with
**fresh offsets**, and the broken member carrying `attempts` and `last_error`.
Re-run the offset-gap query from section 7 against the new bundle.

---

## 11. Delete phase

```bash
du -sh var/sources
$APP delete-sources --cohort 2026-08-14
du -sh var/sources
```

Only sources whose member row belongs to a **VERIFIED** bundle are deleted, and
only at the generation that was archived.

```bash
find var/sources -name '*CN_DN*.pdf' | wc -l     # still there - never archived, never deleted

curl -s -o /tmp/after.pdf "http://localhost:8080/documents/$CRN?kind=INVOICE&format=pdf"
cmp /tmp/after.pdf /tmp/out.pdf && echo "still retrievable with the source gone"
```

---

## 12. Benchmarking

Always use `$APP`, never `./gradlew run`, or you measure Gradle.

```bash
JAVA_OPTS="-Xmx512m -Xlog:gc:file=var/gc.log" /usr/bin/time -l $APP bundler
```

`/usr/bin/time -l` reports maximum resident set size, user and system CPU, and
context switches. The GC log gives heap occupancy and pause totals.

Sample CPU and RSS while it runs:

```bash
( while P=$(pgrep -f "in.porter.archival.app.MainKt" | head -1); [ -n "$P" ]; do
    ps -o %cpu=,rss= -p "$P"; sleep 0.25
  done ) > var/proc.csv &
```

Postgres load during a run:

```sql
SELECT count(*) AS backends, count(*) FILTER (WHERE state='active') AS active
  FROM pg_stat_activity WHERE datname='archival' AND backend_type='client backend';

SELECT xact_commit, tup_returned, tup_inserted, blks_read, blks_hit
  FROM pg_stat_database WHERE datname='archival';
```

See `docs/BENCHMARK-5GiB.md` for the measured results.

---

## 13. Run it on kind

```bash
brew install kind
kind create cluster --config kind/cluster.yaml

docker build -t bundling-poc:local .
kind load docker-image bundling-poc:local --name bundling-poc

kubectl apply -f kind/00-namespace.yaml -f kind/01-storage.yaml \
              -f kind/02-postgres.yaml  -f kind/03-config.yaml
kubectl -n archival wait --for=condition=available deploy/postgres --timeout=180s

kubectl apply -f kind/04-jobs.yaml
kubectl -n archival wait --for=condition=complete job/migrate --timeout=120s
kubectl -n archival wait --for=condition=complete job/seed    --timeout=900s

kubectl apply -f kind/05-crons.yaml -f kind/06-retrieval.yaml
```

Both crons run every five minutes. To trigger one immediately:

```bash
kubectl -n archival create job --from=cronjob/lister  lister-now
kubectl -n archival create job --from=cronjob/bundler bundler-now
kubectl -n archival logs -l job-name --tail=200 -f
```

Retrieval is on `localhost:8080` through the node port. Both crons are capped
at **768Mi**, so a ~1 GiB tar written inside that ceiling is the memory claim
demonstrated rather than asserted.

```bash
kind delete cluster --name bundling-poc
```

---

## 14. Cleanup

```bash
pkill -f "in.porter.archival.app.MainKt"
docker-compose down -v     # removes the volume - WIPES all Postgres data
rm -rf var                 # removes every generated invoice and tar
./gradlew clean
```

A 5 GiB run leaves about 10.3 GB in `var/`. None of it is in git.

---

## 15. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `FileAlreadyExistsException` while seeding | Seeding is create-only by design. Use a different `--cohort`, or reset (section 1) |
| Retrieval returns **502** | The tar is missing or its bytes changed. Check `ls var/archive/**/*.tar` — likely the file was deleted while bundle rows remained |
| Retrieval returns **409** | The bundle is not `VERIFIED` yet. Run `$APP bundler` |
| Retrieval returns **404** | No member row. Either never archived, or you asked for `kind=CN_DN`, which is deliberately not in the worklist |
| `bundler` exits immediately | Nothing to claim. Run `lister` first, or all bundles are already `VERIFIED` |
| Lister plans 0 members | No `oms_orders` rows for that cohort. Check `SELECT created_at::date, count(*) FROM oms_orders GROUP BY 1` |
| `Connection refused` on 55432 | Postgres is not up: `docker-compose up -d` and wait for `pg_isready` |
| Out of disk | A 5 GiB bundle needs ~11 GB. Use `--orders 5000` for a 1 GiB bundle instead |
| Timings look slow and inconsistent | You are running through `./gradlew run`, which adds ~4 s. Use `$APP` |
