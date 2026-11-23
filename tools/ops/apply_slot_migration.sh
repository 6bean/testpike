#!/usr/bin/env bash
# apply_slot_migration.sh -- apply one reference migration file to THIS chain's
# slot database (Flyway is not enabled in dev; db/migration/*.sql is applied
# manually per project convention, then schema-init is regenerated with
# gen_schema_init.sh). Credentials are read from the chain env (never inlined);
# the password only ever travels through the PGPASSWORD environment of the
# docker-exec'd psql, not the command line.
#
# Usage: tools/ops/apply_slot_migration.sh <path/to/Vxxx__name.sql> [--db NAME]
#   --db NAME  target database (default: derived from DB_URL in the chain env)
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
SQL=""
DB=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --db) DB="$2"; shift 2 ;;
    *) SQL="$1"; shift ;;
  esac
done
[[ -n "$SQL" && -f "$SQL" ]] || { echo "USAGE: apply_slot_migration.sh <sql-file> [--db NAME]" >&2; exit 2; }
cd "$REPO"
# shellcheck source=/dev/null
. tools/ops/load_chain_env.sh
DB="${DB:-$(python3 - "$DB_URL" <<'PY'
import re, sys
# JDBC URLs (jdbc:postgresql://host:port/db?params) are the chain-env norm —
# urlparse cannot read them, so peel the prefix and take the first path segment.
u = re.sub(r"^jdbc:", "", sys.argv[1])
tail = u.split("://", 1)[-1]
tail = tail.split("/", 1)[1] if "/" in tail else ""
db = re.split(r"[?;]", tail, maxsplit=1)[0]
if not db:
    raise SystemExit("no database segment in DB_URL")
print(db)
PY
)}"
: "${DB_USERNAME:?DB_USERNAME missing from chain env}"
[[ -n "$DB" ]] || { echo "could not derive database name from DB_URL" >&2; exit 2; }
CONTAINER="$(python3 scripts/chain_slot.py pg-container 2>/dev/null || echo testpike-postgres)"
# In-container psql authenticates over the unix socket like scripts/chain_slot.py does
# (no password ever reaches the command line); PGHOST is pinned so a leaked host-side
# PGPORT/PGHOST (15432) cannot redirect it to the published TCP port.
docker exec -i -e PGHOST=/var/run/postgresql -e PGPORT="" "$CONTAINER" \
    psql -U "$DB_USERNAME" -d "$DB" -v ON_ERROR_STOP=1 -q -f - < "$SQL"
echo "APPLIED $SQL -> $DB@$CONTAINER"
