#!/bin/sh
# I·api_image_builds_starts_answers_and_is_not_published (task S-04 of the spec of HU-OPS-001)
#
# Builds the image, checks that the compose file publishes no port to the host (only the gateway may, norm
# 5.6.1) and has no database service or volume (Annex J, J.7), starts the image with a public key made here
# and asks it for /health and for a protected route. The key is made on the spot and thrown away with the
# container. Docker and openssl are the only requirements; the CI and a developer run this same file.
set -u
cd "$(dirname "$0")/../.." || exit 1

# Git Bash on Windows rewrites container paths; no effect elsewhere
export MSYS_NO_PATHCONV=1

# Same versions from another registry when Docker Hub limits the address (the CI sets both; see ci.yml)
MAVEN_IMAGE="${MAVEN_IMAGE:-maven:3.9.9-eclipse-temurin-21}"
JRE_IMAGE="${JRE_IMAGE:-eclipse-temurin:21.0.8_9-jre}"
RUN_ID="opsapi-deploy-$$"
fail=0

# the pool connects when it is first used, so an address that nobody listens on is enough to start
DB_URI="jdbc:postgresql://localhost:5432/unused"
KEYFILE=
cleanup() {
  docker rm -f "$RUN_ID" >/dev/null 2>&1 || true
  docker rmi "$RUN_ID" >/dev/null 2>&1 || true
  rm -f "$KEYFILE"
}
KEYFILE=$(mktemp)
trap cleanup EXIT
trap 'exit 1' INT TERM

check() { # name, then the command that must succeed
  name=$1; shift
  if reason=$("$@" 2>&1); then echo "PASS  $name"; else echo "FAIL  $name"; printf '%s\n' "$reason" | tail -n 8 | sed 's/^/        /'; fail=1; fi
}

build_image() {
  docker build -q -f deploy/Dockerfile --build-arg MAVEN_IMAGE="$MAVEN_IMAGE" --build-arg JRE_IMAGE="$JRE_IMAGE" -t "$RUN_ID" .
}

compose_has_no_published_port_database_or_volume() {
  json=$(JWT_PUBLIC_KEY=x docker compose -f deploy/compose.yml config --format json 2>&1) || { echo "$json"; return 1; }
  flat=$(printf '%s' "$json" | tr -d ' 
')
  case $flat in *'"published"'*|*'"ports"'*) echo "the file publishes a port"; return 1 ;; esac
  case $flat in *'"expose":["8084"]'*) ;; *) echo "the service does not expose 8084"; return 1 ;; esac
  case $flat in *'"volumes"'*) echo "the file defines a volume"; return 1 ;; esac
  services=$(JWT_PUBLIC_KEY=x docker compose -f deploy/compose.yml config --services)
  [ "$services" = "operations-cost-api" ] || { echo "the file defines more than the service: $services"; return 1; }
}

# A one-line public key, as the service reads it from JWT_PUBLIC_KEY
make_key() {
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 2>/dev/null | openssl rsa -pubout 2>/dev/null \
    | awk 'NF {printf "%s\n", $0}' > "$KEYFILE"
  [ -s "$KEYFILE" ]
}

starts_and_answers() {
  make_key || { echo "could not make a key"; return 1; }
  # the flags of the compose file, so what it declares is what is tried
  docker run -d --name "$RUN_ID" --read-only --tmpfs /tmp --cap-drop ALL --security-opt no-new-privileges -p 127.0.0.1::8084 -e PORT=8084 -e DATABASE_URL="$DB_URI" -e COMMERCIAL_RESERVATION_BASE_URL=http://localhost:8083 -e JWT_PUBLIC_KEY="$(cat "$KEYFILE")" "$RUN_ID" >/dev/null || return 1
  port=$(docker port "$RUN_ID" 8084/tcp | head -n 1 | sed 's/.*://')
  health=000
  for _ in $(seq 1 40); do
    health=$(curl -s --max-time 3 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$port/health" || true)
    [ "$health" = 200 ] && break
    sleep 1
  done
  [ "$health" = 200 ] || { echo "/health answered $health"; docker logs "$RUN_ID" 2>&1 | tail -n 5; return 1; }
  protected=$(curl -s --max-time 3 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$port/api/v1/operations-cost/departures")
  [ "$protected" = 401 ] || { echo "a protected route without a token answered $protected"; return 1; }
}

check "I·api_image_builds" build_image
check "I·api_compose_publishes_no_port_and_defines_no_database" compose_has_no_published_port_database_or_volume
# The README promises that the service stops at start, saying why, when it has no public key
stops_without_a_key() {
  out=$(docker run --rm -e PORT=8084 -e DATABASE_URL="$DB_URI" -e COMMERCIAL_RESERVATION_BASE_URL=http://localhost:8083 "$RUN_ID" 2>&1) && { echo "the service started without a public key"; return 1; }
  printf '%s' "$out" | grep -q 'JWT_PUBLIC_KEY' || { echo "the message does not name JWT_PUBLIC_KEY"; return 1; }
}

check "I·api_image_starts_answers_health_and_protects_the_api" starts_and_answers
# ... and without the address of the database
stops_without_the_database() {
  make_key || return 1
  out=$(docker run --rm -e PORT=8084 -e COMMERCIAL_RESERVATION_BASE_URL=http://localhost:8083 -e JWT_PUBLIC_KEY="$(cat "$KEYFILE")" "$RUN_ID" 2>&1) && { echo "the service started without a database address"; return 1; }
  printf '%s' "$out" | grep -q 'DATABASE_URL' || { echo "the message does not name DATABASE_URL"; return 1; }
}

# The image declares a health check on /health: the container becomes healthy once the service answers
becomes_healthy() {
  [ -n "${RUN_ID:-}" ] && docker inspect "$RUN_ID" >/dev/null 2>&1 || { echo "the container of the previous check is not there"; return 1; }
  status=none
  for _ in $(seq 1 60); do
    status=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$RUN_ID")
    [ "$status" = healthy ] && break
    sleep 2
  done
  [ "$status" = healthy ] || { echo "the container is '$status', expected healthy"; return 1; }
}

check "I·api_image_becomes_healthy" becomes_healthy
check "I·api_image_stops_without_a_public_key_and_says_why" stops_without_a_key
check "I·api_image_stops_without_a_database_address_and_says_why" stops_without_the_database

[ "$fail" = 0 ] && echo "ALL CHECKS PASSED" || echo "SOME CHECKS FAILED"
exit "$fail"
