# Runbook

Two ways to run it. `docker-compose` is the fast loop; `kind` is the one to
demo, because it runs the two crons on a real schedule in a real cluster.

Nothing here writes outside `var/` (compose) or the PVCs (kind), and neither is
in git history.

---

## A. Local loop — docker-compose

```bash
docker-compose up -d                               # postgres on :55432
./gradlew run --args="migrate"
./gradlew run --args="seed --orders 5000 --size-kb 200"
```

5,000 orders at 200 KB produces ~1.05 GB across PDFs and their HTML twins, plus
250 unreferenced CN/DN objects that no worklist row points at.

```bash
./gradlew run --args="lister"                      # cron 1: plan the day
./gradlew run --args="status"
./gradlew run --args="bundler"                     # cron 2: frame + verify
./gradlew run --args="serve"                       # retrieval on :8080
```

Then, in another shell:

```bash
curl -s -D- -o /tmp/out.pdf "http://localhost:8080/documents/CRN7000000042?kind=INVOICE&format=pdf"
file /tmp/out.pdf        # a real PDF, pulled out of a ~1 GB tar by byte range
```

The `X-Archive-Source` response header names the tar, the offset and the length
the bytes came from.

### Prove the tar is a real tar

```bash
tar -tvf var/archive/<yyyy>/<mm>/<dd>/bundle-00000.tar | head
tar -xOf var/archive/<yyyy>/<mm>/<dd>/bundle-00000.tar \
    order-invoices/<render-id>/invoice_CRN7000000042.pdf > /tmp/extracted.pdf
```

`tar -xf` reads it with none of our code. That is the point of USTAR.

---

## B. Cluster demo — kind

Needs kind: `brew install kind`.

```bash
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

Both crons run every five minutes. To watch, or to trigger one immediately:

```bash
kubectl -n archival logs -l job-name --tail=200 -f
kubectl -n archival create job --from=cronjob/lister  lister-now
kubectl -n archival create job --from=cronjob/bundler bundler-now
```

Retrieval is on `localhost:8080` through the node port.

Both crons are capped at **768Mi**. A ~1 GiB tar being written inside that
ceiling is the memory claim in the LLD, demonstrated rather than asserted.

---

## C. The demos worth showing

### 1. A rerun costs nothing

```bash
./gradlew run --args="lister"     # run it twice
./gradlew run --args="status"     # member count does not move
```

`ON CONFLICT (cohort_date, source_object, source_generation) DO NOTHING` makes
a rerun one metadata call and no read.

### 2. An unreadable member fails the whole bundle and re-plans

```bash
./gradlew run --args="lister"
./gradlew run --args="demo-corrupt"      # truncates one source object
./gradlew run --args="bundler"
```

The bundle goes `FAILED`, the survivors are re-pointed at a new bundle with new
offsets, and the broken member carries `attempts` and `last_error`. Offsets are
pre-planned, so skipping one file would shift every later offset — the bundle is
never written with a gap and never written short.

### 3. Verification has to pass before anything is trusted

The bundler prints all six checks per bundle. Check 6 is parked by default; set
`verify.full.byte.scan=true` in `engine.properties` to turn it on.

### 4. Delete the sources, then retrieve anyway

```bash
./gradlew run --args="delete-sources"
curl -s -o /tmp/after.pdf "http://localhost:8080/documents/CRN7000000042?kind=INVOICE&format=pdf"
```

Only sources whose member row belongs to a **VERIFIED** bundle are deleted, and
only at the generation that was archived. The unreferenced CN/DN objects are
still on disk afterwards: a worklist-driven job archives neither case and
deletes nothing it never saw.

---

## What this PoC does not cover

- **Snowflake CDC and the partition drop.** Cannot be done locally. The drop
  guard — count the month's rows in the warehouse before detaching — is
  described in the LLD and stubbed here.
- **Real GCS.** Everything goes through the `ObjectStore` port; swapping the
  filesystem implementation for GCS is one class.
- **Archive storage class and retrieval billing.** Confirmed from Google's
  pricing page rather than measured.
