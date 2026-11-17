#!/usr/bin/env bash
# End-to-end local demo. Stops at the first failure.
set -euo pipefail
cd "$(dirname "$0")/.."

ORDERS="${ORDERS:-5000}"
SIZE_KB="${SIZE_KB:-200}"

run() { echo; echo "=== $* ==="; ./gradlew -q run --args="$*"; }

docker-compose up -d
until docker exec bundling-poc-db pg_isready -U archival -d archival >/dev/null 2>&1; do sleep 1; done

run "migrate"
run "seed --orders ${ORDERS} --size-kb ${SIZE_KB}"
run "lister"
run "status"
run "bundler"
run "status"

echo
echo "=== bundles on disk ==="
find var/archive -name '*.tar' -exec ls -lh {} \;

echo
echo "Now run:  ./gradlew run --args=\"serve\"   and curl a document."
