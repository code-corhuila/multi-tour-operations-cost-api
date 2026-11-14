# multi-tour-operations-cost-api

> The service of the operations-cost domain: the departures of the tours, their execution and their costs

Part of the **Multi Tour** distributed system — team `multi-tour`, Grupo 2.
Governance and documentation live in [`multi-tour-docs`](https://github.com/code-corhuila/multi-tour-docs).

## What it is

A Java 21 / Spring Boot 3.5 service in three Maven modules: `core` (the domain and its ports, no framework), `adapters` (HTTP, JDBC and the HTTP clients of other domains) and `app` (the composition root). It owns the departures of the tours (HU-OPS-001), the vehicles they use (HU-OPS-002, part A) and the record of how they ran (HU-OPS-003, part A):

- `POST /api/v1/operations-cost/departures` schedules a departure of a tour of the catalog (the Tenant Administrator, with an `Idempotency-Key`), and `PUT /api/v1/operations-cost/departures/{departureCode}/schedule` changes its date while it has no reservations. Both write the event of the change (`SalidaScheduled`, `SalidaRescheduled`) in the same transaction.
- `POST` and `GET /api/v1/operations-cost/vehicles` register and list the vehicles of the tenant (name and capacity, with an `Idempotency-Key`). A departure takes `vehicleIds` when it is scheduled, and `POST /departures/{departureCode}/vehicles` adds vehicles to one that exists; a vehicle is never on two departures whose times overlap, and the capacity of a departure that needs transport is the lesser of the tour capacity and the capacity of its vehicles. Every change of vehicles writes `SalidaVehiclesChanged` in the same transaction.
- `GET /api/v1/operations-cost/departures` lists the departures of the tenant (most recent scheduled start first, paged, with a filter by state) and `GET /departures/{departureCode}` reads one. `POST /departures/{departureCode}/executions` records the real start and `POST /departures/{departureCode}/finish` the end or the interruption (with its cause), each with an `Idempotency-Key`; they write `SalidaExecutionStarted` and `SalidaFinalized` in the same transaction. A start is never before the scheduled one nor in the future, an end is never before the start nor in the future, and an interruption still ends the departure finished. These four routes need the permission `MANAGE_DEPARTURE_EXECUTION` in the claim `permissions` of the token (the Tenant Administrator holds it by profile, an Operational Collaborator when it was granted); scheduling and the vehicles stay the Administrator's.
- The tour is read from `commercial-reservation` (its catalog) and the seats held from its list of departures, with the token of the caller; neither is ever read from its database.
- Three internal routes under `/api/v1/operations-cost/internal/outbox`, open only to the SERVICE role, let job 7 (`multi-tour-worker-event-relay`) read the pending events and mark each one delivered or failed.
- `GET /health` without a token, the validation of the RS256 token by the service itself, one error body for every failure, the correlation id, the limits of the server and of the database pool, and a 16 KB limit on a body.

## How to start it

The image is built and run by the composition of the platform: `multi-tour-infra-postgres` includes `deploy/compose.yml`, which defines the service `operations-cost-api` on the network `platform`. It exposes 8084 and publishes no port: only the gateway is reachable from the host. It needs `JWT_PUBLIC_KEY` (or `JWT_PUBLIC_KEY_FILE`), the public key of the security microservice, and `DATABASE_URL` (a `jdbc:postgresql://` address) with `DATABASE_USER` and `DATABASE_PASSWORD` of the user `operations_cost_app`; without the key or the address the service stops at start and says which one. To try the image alone, `sh deploy/tests/deploy-check.sh` builds it, starts it with a key made on the spot and asks it for `/health` and for a protected route; it needs Docker and openssl.

## Where the data is

In the schema `operations_cost` of the single PostgreSQL instance of `multi-tour-infra-postgres`, with the user `operations_cost_app`. The schema is built by `multi-tour-operations-cost-db`; this repository holds no schema or migration.

## How to test it

`mvn -B verify` with Java 21; the persistence tests need a PostgreSQL with the schema of `multi-tour-operations-cost-db`, which `sh scripts/prepare-test-db.sh up` makes (a throwaway container) and prints as `TEST_DATABASE_URL`; without it they are skipped, and the CI fails if any was. `sh deploy/tests/deploy-check.sh` tests the image. Both run on every pull request to `develop` and `qa` (`ci.yml`). JaCoCo measures the line coverage of every module and `core`, the domain, fails the build under 95 % (it was 99.6 % when the floor was set); `adapters` is measured too, but most of its tests live in `app` and the persistence ones need `TEST_DATABASE_URL`, so it has no floor of its own. The report is in `<module>/target/site/jacoco/`.

## What is missing

The rest of the domain: removing or replacing the vehicles of a departure (HU-OPS-002 part B, it waits for a route of `commercial-reservation` that lowers and restores the ceiling), the alert, the mark and the regularization of a departure that did not start or end (HU-OPS-003 part B, they need job 3), the services delivered, the costs and the cancellation (HU-OPS-004 to HU-OPS-007); the check of the database in `/health`; `booked` is the seats held that `commercial-reservation` lists for the departure, which today only lists departures that have not started, so a departure in execution or finished answers 0 (declared gap with the owner) and the answers of scheduling, rescheduling and adding vehicles always say 0; and `SalidaRescheduled` is published but no service consumes it yet. The declared gaps are in the specs of `07-api/specs/operations-cost/multi-tour-operations-cost-api/`.

## Branching

Three permanent branches. **None of them accepts a direct commit** — you enter through a child
branch and leave through a Pull Request.

```
develop  <--PR--  feat/... fix/... chore/...
qa       <--PR--  qa/...
main     <--PR--  release/...  hotfix/...
```

Promotion happens **by re-application** (`git cherry-pick -x`), never by merging one permanent
branch into another: `merge develop -> qa` and `merge qa -> main` do not exist in this model.

`main` requires **1 approval from `ariel5253`**. On `develop` and `qa` the team sets its own review
rule.

Full policy: `00-governance/branching-policy.md` in `multi-tour-docs`.
