# CP-1369 — review findings, parked

**Status: parked.** Nothing here is actioned yet. Picked up when the LLD comes
back from lead review and the finance-repo question is settled.

Everything below was verified against production data or repo code on
**17 Nov 2026**, not taken from the review on trust. Where something could not
be verified, it says so.

**The design itself is not in question.** Bundling, USTAR framing, pre-planned
offsets, the verification gate and ranged-read retrieval all stand. What needs
changing is the Lister's *input*, plus three bugs.

---

## 1. Confirmed blockers

### 1.1 The worklist misses CN/DN and every superseded render

**Verified in code** (`coc-work/invoicing-platform`):

```kotlin
// AdjustmentPdfClient.kt:164  — CN/DN gets a FRESH random folder
renderId = UUID.randomUUID().toString()
// AdjustmentPdfClient.kt:194  — and OVERWRITES the order's URI
omsOrdersRepo.updateInvoicePdfUri(orderId, pdfUri)

// InvoicePdfClient.kt:129     — every render, re-renders included
renderId = UUID.randomUUID().toString()
// InvoicePdfClient.kt:170     — same overwrite
omsOrdersRepo.updateInvoicePdfUri(orderId, pdfUri)
```

So `orders.invoice_pdf_uri` holds only the **latest** document for an order.
A re-render is a **new path**, not a new generation of the same path.

**Two statements in the LLD are therefore false** and must be corrected:
- "CN/DN sit in the same render folder"
- "both generations have their own member rows"

**Measured magnitude — far smaller than the review implies.** 14 Aug 2026:

| | |
|---|---|
| orders with a URI | 939,673 |
| URI points at a CN/DN | **931 (0.099%)** |

So roughly 1 in 1,000, not a general failure of the worklist.

**Mitigating fact:** the CN/DN is rendered with
`PostRenderDirective.PrependPdf(existingInvoicePdfUrl)` — the original invoice
is prepended into it. The *content* is archived even when the standalone PDF
object is not.

**What is genuinely lost:** superseded objects become orphans — never archived,
never deleted. Very likely a large part of the 1.06 bn (bucket) vs ~930 M
(worklist-reachable) gap, and overlaps open question 9 (`_V2` files).

**Candidate fix (not decided):** drive the worklist from GCS's own record of
what exists — object notifications into a small log table going forward, a GCS
inventory report for backfill. Cohort then becomes the *object's* creation
date, not the order's. Would also retire open question 11.

### 1.2 Legacy URI shape breaks backfill

Our parser requires `/b/<bucket>/o/<path>?generation=<n>`. Measured:

| Day | URIs | No `generation=` | Not a GCS API URL |
|---|---|---|---|
| 2024-03-07 | 274,088 | **274,075 (99.995%)** | **274,075** |
| 2025-06-10 | 431,202 | 0 | 0 |
| 2026-08-14 | 939,673 | 0 | 0 |

The cloud-function era stored **CDN URLs**. Backfill of 2024-03 would skip
essentially every order. **T−90 is unaffected** — recent data is 100% clean.

### 1.3 Checks 1–5 cannot catch bytes at the wrong offset

Swap two members' payloads: total size identical, manifest agrees (it is built
from the same rows), counts agree. **All five pass.** Only a crc of the actual
bytes catches it, and retrieval would then 502 forever — after the delete phase
the documents are gone.

Also true: **check 4 is a no-op** — `offset_bytes` is `NOT NULL` and the Lister
always sets it. (Already noted in the PoC's `VerifyBundle`.)

**Fix:** make check 6 mandatory, and cheap by ordering it right — write the tar
in **Standard**, stream it back once *in-region*, crc every member, and only
then lifecycle it to Archive. Mandatory at 100% at least until Phase 4.

### 1.4 A crash right after upload wedges the bundle

Upload completes → pod dies before `WRITTEN` is persisted → every retry gets
**412** from `ifGenerationMatch=0`. In the PoC that exception is not caught at
all and crashes the bundler.

**Fix:** on 412, read the existing object's size and md5, compare against the
plan, adopt it if they match; otherwise delete and retry (nothing reads an
unverified tar).

---

## 2. Confirmed documentation error

**Observation 2 is wrong as written.** "The bill is driven by how many objects
we have, not how many bytes" — GCS has **no per-object storage charge**. The
₹3.66 L/month is bytes x rate.

Object count drives the **one-time transition cost** and **per-operation**
costs, which is exactly why Approach A costs ₹68 L. The conclusion (bundle
first, then tier) is unchanged; the explanation is not.

Also to check: whether the ₹2.8 L/month saving figure includes the 29.85 TiB of
route maps that are declared out of scope.

---

## 3. Parked design options — deliberately NOT doing now

- **Replace the 800 M-row index with index files in GCS (Haystack pattern).**
  Real savings: the 24-month partition window, pg_partman, the Snowflake CDC
  tier and open question 2 all disappear. Cost: the archive stops being
  self-contained, and checks 4/5 lose their independent cross-check (the
  manifest would verify only against itself). **Not now.**
- **Narrower version worth revisiting:** keep member rows for the live 24
  months exactly as designed, and use a fixed-width searchable manifest *instead
  of Snowflake CDC* for everything older. Archive becomes self-describing,
  no warehouse dependency, open question 2 disappears. ~11,000 bundle rows a
  year. Cold reads ~17 ranged reads each, about ₹1.50/day at measured volumes.
- **Look documents up by path, keeping CDN URLs working.** Would mean no
  consumer has to migrate. Related: CDN migration may be a prerequisite for
  Phase 4, which the LLD currently lists as out of scope.
- **Several Bundler workers.** `SKIP LOCKED` already makes this safe. An hourly
  CronJob with `Forbid` is the wrong shape for draining a 1.06 bn-object
  backfill (~31 days of continuous running at 400 obj/s). Run in `asia-south1`
  to avoid egress.
- **No-code wins, each needing its own approval:** lifecycle-delete the route
  map PNGs (~18% of bytes); ask whether the HTML twins need keeping past 90
  days — the PDF is the legal document, and dropping HTML halves object count,
  index rows and backfill time.

---

## 4. Not verified / pushed back on

- **"The design is wrong as written"** is overstated. One 0.1%/day input gap,
  one backfill-only parser issue, two ordinary bugs.
- **"Invoicing prod instance 92.5% full on 2 vCPU"** — unverified; the reviewer
  states it is from old notes, not re-checked. The same unverifiable claim
  appeared in an earlier critique.
- **Approach A vs Coldline and Autoclass** has not been compared. A reviewer
  will ask why they were ruled out. Autoclass has a per-object management fee
  which at ~1 bn objects likely eats the saving — needs confirming against the
  pricing page.

---

## 5. Action order when this is picked up

| # | Change | Size | Touches |
|---|---|---|---|
| 1 | Handle 412 by adopting a matching existing object | Small | Bundler |
| 2 | Check 6 mandatory, run in Standard before tiering | Small | Verify |
| 3 | Parse legacy CDN URI shape, or drive backfill from GCS inventory | Medium | Backfill only |
| 4 | Worklist must pick up CN/DN and superseded renders | **The real one** | Cron 1 input only |
| 5 | Correct observation 2 (bytes vs object count) | Doc | LLD §1 |

Only #4 touches the design, and it changes **what feeds cron 1** — not the
bundling, the tar, the schema or retrieval.

---

## 6. Already fixed in this PoC (for reference)

Found by running it, not by reading it:

- Cohort purity: the id range is an optimisation, the `created_at` predicate is
  the correctness boundary. Without it a day whose ids are out of order writes
  members into the wrong partition **with every check still passing**.
- Batched inserts were one network round trip per row — `reWriteBatchedInserts`
  cut plan-and-insert from 448 to 247 microseconds per member.
- The object store now discards its own partial writes (a failed bundle was
  leaving a 0-byte `.tar`).
- Seeded CRNs were not unique across cohorts.
- Retry bundle names stacked (`-r1-r1`); now parsed and incremented.

## 7. Production facts worth not re-deriving

- `orders.id` is sequential with time; **0.28% of adjacent rows invert, by at
  most 718 ms**. Across 11 sampled days (8 recent, 3 backfill-era), **zero**
  rows fell in the wrong day's id range.
- `order_id` (the CRN) is **random** — correlation with `id` is 0.0008, and it
  goes backwards 50.00% of the time. It cannot be binary-searched.
- `orders` id space: 1 → 861,450,292, so ~30 probes per boundary.
- 14 Aug 2026: 1,576,178 orders → 939,424 invoices with a PDF (~60%), id range
  742,673,004 → 744,252,694 with 3,513 gaps.
- The only index on `orders` declared in the OMS repo is
  `uniq_idx_order_id_conditional_v1 (order_id) WHERE created_at >= '2022-12-28'`.
  The repo does **not** hold the full index list — `\d orders` on the replica is
  the only way to settle whether `created_at` is indexed.
