#!/bin/sh
# Prepares the database that the integration tests run against: a throwaway PostgreSQL (the image of
# multi-tour-infra-postgres) with the schema that multi-tour-operations-cost-db builds, applied by the migration runner of
# that repository. This repository makes no schema (norm 5.2.1), so the tests are not written against a shape the real
# database never has.
#
#   sh scripts/prepare-test-db.sh up     prints TEST_DATABASE_URL on stdout, everything else goes to stderr
#   sh scripts/prepare-test-db.sh down   removes the container
#
# The db repository is taken from DB_REPO (a sibling checkout, ../multi-tour-operations-cost-db by default) or, when it is
# not there, cloned from DB_REPO_URL at DB_REPO_REF, a branch or a commit (the CI pins a commit, so the result does not
# depend on when it runs). The commit that was used is always written to stderr. Docker and git are the only requirements;
# the CI and a developer run this same file. No secret is versioned: the passwords are made on the spot and thrown away
# with the container.
set -eu
cd "$(dirname "$0")/.."

# Git Bash on Windows rewrites container paths; no effect elsewhere
export MSYS_NO_PATHCONV=1

NAME="${TEST_DB_NAME:-opsapi-testdb}"
PORT="${TEST_DB_PORT:-55433}"
POSTGRES_IMAGE="${POSTGRES_IMAGE:-postgres:18.6-alpine}"
DB="multitour_test"
DB_REPO="${DB_REPO:-../multi-tour-operations-cost-db}"
DB_REPO_URL="${DB_REPO_URL:-https://github.com/code-corhuila/multi-tour-operations-cost-db.git}"
DB_REPO_REF="${DB_REPO_REF:-develop}"

log() { echo "$@" >&2; }

# Docker Hub and the registries can answer 429 to a shared address: wait and try again. retry ATTEMPTS SECONDS COMMAND...
retry() {
  attempts=$1; wait=$2; shift 2
  tried=1
  until "$@"; do
    [ "$tried" -ge "$attempts" ] && return 1
    log "failed (attempt $tried of $attempts), waiting ${wait}s"
    tried=$((tried + 1)); sleep "$wait"
  done
}

case "${1:-}" in
  down)
    docker rm -fv "$NAME" >/dev/null 2>&1 || true
    exit 0
    ;;
  up) ;;
  *)
    log "usage: sh scripts/prepare-test-db.sh up|down"
    exit 2
    ;;
esac

clone=
RUNNER_IMAGE="$NAME-runner"
cleanup() {
  [ -z "$clone" ] || rm -rf "$clone"
  docker rmi "$RUNNER_IMAGE" >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [ ! -f "$DB_REPO/changelog/changelog-master.yaml" ]; then
  log "cloning $DB_REPO_URL at $DB_REPO_REF"
  clone=$(mktemp -d)
  git init --quiet "$clone/db" >&2
  git -C "$clone/db" fetch --quiet --depth 1 "$DB_REPO_URL" "$DB_REPO_REF" >&2
  git -C "$clone/db" checkout --quiet FETCH_HEAD >&2
  DB_REPO="$clone/db"
fi
# the native docker client of Windows needs a Windows path for the build context; elsewhere pwd -W does not exist
DB_REPO=$(cd "$DB_REPO" && { pwd -W 2>/dev/null || pwd; })
log "db repository: $DB_REPO at $(git -C "$DB_REPO" rev-parse --short HEAD 2>/dev/null || echo 'unknown commit')"

ADMIN_PASSWORD="admin-$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')"
APP_PASSWORD="app-$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')"

docker rm -fv "$NAME" >/dev/null 2>&1 || true
retry 3 20 docker pull -q "$POSTGRES_IMAGE" >/dev/null || { log "the PostgreSQL image could not be downloaded"; exit 1; }
docker run -d --name "$NAME" -p "127.0.0.1:$PORT:5432" -e POSTGRES_PASSWORD="$ADMIN_PASSWORD" -e POSTGRES_DB="$DB" "$POSTGRES_IMAGE" >/dev/null \
  || { log "the container did not start: is the port $PORT in use? set TEST_DB_PORT to another"; docker rm -f "$NAME" >/dev/null 2>&1 || true; exit 1; }

psql_admin() { docker exec -e PGPASSWORD="$ADMIN_PASSWORD" "$NAME" psql -h 127.0.0.1 -U postgres -d "$DB" -v ON_ERROR_STOP=1 -tA "$@"; }
tries=0
until psql_admin -c "SELECT 1" >/dev/null 2>&1; do
  tries=$((tries + 1)); [ "$tries" -gt 60 ] && { log "the throwaway PostgreSQL did not start"; exit 1; }
  sleep 1
done

# What multi-tour-infra-postgres leaves for the domain: the user of the connection, with the schema as its search path
psql_admin -c "CREATE ROLE operations_cost_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '$APP_PASSWORD'" \
           -c "ALTER ROLE operations_cost_app SET search_path = operations_cost" >/dev/null || exit 1

retry 3 20 docker build -q -f "$DB_REPO/deploy/Dockerfile" -t "$RUNNER_IMAGE" "$DB_REPO" >/dev/null || { log "the runner image did not build"; exit 1; }
docker run --rm --network "container:$NAME" \
  -e LIQUIBASE_COMMAND_URL="jdbc:postgresql://localhost:5432/$DB" \
  -e LIQUIBASE_COMMAND_USERNAME=postgres -e LIQUIBASE_COMMAND_PASSWORD="$ADMIN_PASSWORD" \
  "$RUNNER_IMAGE" --search-path=/workspace --changelog-file=changelog/changelog-master.yaml \
  --database-changelog-table-name=databasechangelog_operations_cost \
  --database-changelog-lock-table-name=databasechangeloglock_operations_cost update >&2 \
  || { log "the migrations of the db repository did not apply"; exit 1; }

echo "jdbc:postgresql://localhost:$PORT/$DB?user=operations_cost_app&password=$APP_PASSWORD"
