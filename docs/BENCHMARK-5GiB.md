# Benchmark — one 5 GiB bundle, production configuration

Measured **18 Nov 2026** on a clean database and a freshly generated corpus.
The app ran from `build/install` as a plain JVM process, **not through Gradle**,
so nothing here includes build-tool overhead.

## Setup

| | |
|---|---|
| Host | 8 cores, 16 GB RAM (macOS, local SSD) |
| Heap | `-Xmx512m` — the LLD's ceiling |
| Config | LLD **production** column via env: `fetchConcurrency=32`, `queueBytes=64MiB`, `uploadChunkBytes=16MiB`, `maxBundleMembers=100000`, `targetBundleBytes=6GiB` |
| Corpus | 26,000 orders x 200 KB PDFs + HTML twins = **52,000 members**, 5.3 GB on disk |
| Result | **one tar of 5,375,413,248 bytes = 5.006 GiB**, 52,001 tar entries (52,000 + MANIFEST) |

## Headline timings

| Phase | Wall | Notes |
|---|---|---|
| Lister (cron 1) | **18.7 s** | 52,000 members planned; metadata only, no payload read |
| Bundler framing | **36 s** warm / **53 s** cold | read 52,000 objects, write the 5 GiB tar |
| Checks 1-5 | **0.85 s** | |
| Check 6 at 100% | **29.2 s** | reads all 5 GiB back and crc32c's every member |
| **Bundler total** | **54.4 s** without check 6, **65.8 s** with it | |

Framing throughput, warm: **1,444 members/s, ~142 MiB/s**.
Check 6 read-back: **~171 MiB/s**. Enabling it costs **+81%** on framing time.

Cold vs warm is OS page cache — the first run read from disk, the second from
cache. Both are reported because neither is the production number: against GCS
the limit will be network round trips, not local I/O.

## Resources — the bundler, 5 GiB run with check 6 on

| | |
|---|---|
| CPU, mean | **72% of one core** (9% of an 8-core host) |
| CPU, peak | 144% of one core |
| CPU total | 17.7 s user + 28.4 s sys = 46.1 s over 65.8 s wall |
| **RSS peak** | **325 MiB** (`/usr/bin/time`: 343 MB max resident) |
| RSS mean | 156 MiB |
| Threads | peak 61, mean 44 (32 readers + Netty + JVM) |
| **Heap** | peak **223 MiB** before collection, 125 MiB after; committed 258 MiB **of the 512 MiB ceiling** |
| GC | **182 young pauses, 617 ms total = 0.94% of wall**, longest 43.6 ms, **zero full GCs** |
| Queue | peak **6,405 KiB used of a 64 MiB budget**, 64 reads in flight |

**The memory claim holds with room to spare.** A 5 GiB tar was assembled in a
223 MiB peak heap — the queue never used more than 10% of its budget, and the
collector never once had to do a full pass.

## Postgres during the bundler

| | |
|---|---|
| Client backends | peak **5**, mean 5.0 (pool capped at 4 + 1 for the sampler) |
| Backends actively running a query | peak **1**, mean 1.00 |
| Transactions committed | 242 |
| Tuples returned / fetched | 181,372 / 127,875 |
| Tuples inserted / updated | 3 / 16 |
| Buffer hits / disk reads | 49,441 / **69** |

The database is nearly idle through the whole run: **one active query at a
time**, 69 physical reads in 66 seconds. The Bundler is an I/O job against
object storage that happens to hold a Postgres connection, not a database
workload.

## Index cost — the number that extrapolates worst

| | |
|---|---|
| 52,000 member rows | heap 11 MB + indexes 13 MB = **24 MB** |
| **Per member row** | **475 bytes** including indexes |

**At the LLD's 800 M live rows that is ~380 GB, not the 135-225 GB the LLD
estimates** — between 1.7x and 2.8x over. Worth re-checking before committing
to a 24-partition window on a shared instance.

Mitigation already suggested in review: store `render_id` as a UUID plus a
filename enum instead of the ~80-character `source_object` text, and `crc32c`
as an INT. That should cut roughly two thirds, bringing it near the original
estimate.

## Retrieval from the 5 GiB tar

30 random documents, through the HTTP API, including the index lookup, the
ranged read and the crc32c check:

| min | median | p95 | max |
|---|---|---|---|
| 4.1 ms | **6.0 ms** | 10.1 ms | 15.9 ms |

Bundle size does not enter the cost — a document at byte 1,103,072,768 of a
5 GiB tar costs the same as one at byte 512.

## What this does and does not predict

**Does:** memory behaviour, CPU shape, GC pressure, database load, index size
per row, and that the pipeline's bounded queue works as designed under the
production concurrency setting.

**Does not:** wall-clock throughput against GCS. Local reads are microseconds;
GCS reads are 20-50 ms round trips, so production is bounded by
`fetchConcurrency / latency`, not by this machine. At 32 readers and ~30 ms
that is ~1,000 objects/s, against the LLD's assumed 400/s — so the LLD's
estimate of ~78 minutes for a 1.88 M-object day stays conservative.

**One figure that does carry over:** check 6 costs about +80% of framing time.
Doing it in Standard before tiering, as the review suggests, is what makes that
affordable.
