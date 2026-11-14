# multi-tour-operations-cost-api

> The service of the operations-cost domain: the departures of the tours, their execution and their costs

Part of the **Multi Tour** distributed system — team `multi-tour`, Grupo 2.
Governance and documentation live in [`multi-tour-docs`](https://github.com/code-corhuila/multi-tour-docs).

## What it is

A Java 21 / Spring Boot 3.5 service in three Maven modules: `core` (the domain and its ports, no framework), `adapters` (HTTP, JDBC and the HTTP clients of other domains) and `app` (the composition root). It owns the departures of the tours (HU-OPS-001), the vehicles they use (HU-OPS-002), the record of how they ran, with the alert, the mark and the regularization of one that nobody started or closed (HU-OPS-003), what each reservation received (HU-OPS-004), the extraordinary cancellation of a reservation in execution (HU-OPS-005) and the operational costs (HU-OPS-007):

- `POST /api/v1/operations-cost/departures` schedules a departure of a tour of the catalog (the Tenant Administrator, with an `Idempotency-Key`), and `PUT /api/v1/operations-cost/departures/{departureCode}/schedule` changes its date while it has no reservations. Both write the event of the change (`SalidaScheduled`, `SalidaRescheduled`) in the same transaction.
- `POST` and `GET /api/v1/operations-cost/vehicles` register and list the vehicles of the tenant (name and capacity, with an `Idempotency-Key`). A departure takes `vehicleIds` when it is scheduled, and `POST /departures/{departureCode}/vehicles` adds vehicles to one that exists; a vehicle is never on two departures whose times overlap, and the capacity of a departure that needs transport is the lesser of the tour capacity and the capacity of its vehicles. Every change of vehicles writes `SalidaVehiclesChanged` in the same transaction.
- `GET /api/v1/operations-cost/departures` lists the departures of the tenant (most recent scheduled start first, paged, with a filter by state) and `GET /departures/{departureCode}` reads one. `POST /departures/{departureCode}/executions` records the real start and `POST /departures/{departureCode}/finish` the end or the interruption (with its cause), each with an `Idempotency-Key`; they write `SalidaExecutionStarted` and `SalidaFinalized` in the same transaction. A start is never before the scheduled one nor in the future, an end is never before the start nor in the future, and an interruption still ends the departure finished. A departure answers `startAlert` (it is still scheduled and its scheduled start passed; derived for each answer, never stored) and `pendingRegularization` (the mark of a departure past its estimated end with no closing; it is not a state and the passing of time never closes the departure). `POST /departures/{departureCode}/regularization` regularizes a scheduled departure that carries the mark, as executed or interrupted, with the real start and end, who regularized and when; a real start earlier than the scheduled one needs its reason; it writes `SalidaExecutionStarted` and then `SalidaFinalized` in the same transaction, and `not carried out` is refused until HU-OPS-006. The routes of this bullet, of the next two and of the costs need the permission `MANAGE_DEPARTURE_EXECUTION` in the claim `permissions` of the token (the Tenant Administrator holds it by profile, an Operational Collaborator when it was granted); scheduling and the vehicles stay the Administrator's.
- `POST /departures/{departureCode}/services` saves what each reservation of a departure in execution received: delivered or not, the travelers served, the cause of every traveler not served, and a no-show that stays charged; the reservations come from `commercial-reservation` and are never copied, and `GET /departures/{departureCode}` answers them with the real base. `POST` and `GET /departures/{departureCode}/costs` record and list the operational costs, calculated from the catalogued cost and the travelers served or typed as a real expense, always apart from the commercial value.
- `POST /departures/{departureCode}/reservations/{reservationId}/extraordinary-cancellation` records the extraordinary cancellation of one reservation in execution of a departure in execution, with its justification (1 to 500 characters), who recorded it and when. The record, the event `ExtraordinaryCancellationRecorded` and the `Idempotency-Key` are written in one transaction; the reservation is never written here (its owner decides to reschedule or to refund), a reservation is cancelled once, the answer of the departure shows each cancellation next to its reservation, and saving the services of a cancelled reservation is refused. It answers `503` and writes nothing when the reservations cannot be read.
- The tour is read from `commercial-reservation` (its catalog) and the seats held from its list of departures, with the token of the caller; neither is ever read from its database.
- Three internal routes under `/api/v1/operations-cost/internal/outbox`, open only to the SERVICE role, let job 7 (`multi-tour-worker-event-relay`) read the pending events and mark each one delivered or failed. A fourth, `POST /api/v1/operations-cost/internal/departures/pending-regularization?limit=` (SERVICE role, 1 to 500, 100 by default), lets job 3 (`multi-tour-worker-departure-deadlines`) mark, across tenants, the open departures past their estimated end, oldest first; it answers only a count, writes `SalidaMarkedPendienteDeRegularizacion` with each mark in the same transaction, and is safe to repeat. Two more, under `/api/v1/operations-cost/internal/tenants/{tenantId}/departures/{departureCode}/` (SERVICE role), preview and replace the whole set of vehicles of a scheduled departure for the saga `change-departure-vehicles` (HU-OPS-002 part B).
- `GET /health` without a token, the validation of the RS256 token by the service itself, one error body for every failure, the correlation id, the limits of the server and of the database pool, and a 16 KB limit on a body.

## How to start it

The image is built and run by the composition of the platform: `multi-tour-infra-postgres` includes `deploy/compose.yml`, which defines the service `operations-cost-api` on the network `platform`. It exposes 8084 and publishes no port: only the gateway is reachable from the host. It needs `JWT_PUBLIC_KEY` (or `JWT_PUBLIC_KEY_FILE`), the public key of the security microservice, and `DATABASE_URL` (a `jdbc:postgresql://` address) with `DATABASE_USER` and `DATABASE_PASSWORD` of the user `operations_cost_app`; without the key or the address the service stops at start and says which one. To try the image alone, `sh deploy/tests/deploy-check.sh` builds it, starts it with a key made on the spot and asks it for `/health` and for a protected route; it needs Docker and openssl.

## Where the data is

In the schema `operations_cost` of the single PostgreSQL instance of `multi-tour-infra-postgres`, with the user `operations_cost_app`. The schema is built by `multi-tour-operations-cost-db`; this repository holds no schema or migration.

## How to test it

`mvn -B verify` with Java 21; the persistence tests need a PostgreSQL with the schema of `multi-tour-operations-cost-db`, which `sh scripts/prepare-test-db.sh up` makes (a throwaway container) and prints as `TEST_DATABASE_URL`; without it they are skipped, and the CI fails if any was. `sh deploy/tests/deploy-check.sh` tests the image. Both run on every pull request to `develop` and `qa` (`ci.yml`). JaCoCo measures the line coverage of every module and `core`, the domain, fails the build under 95 % (it was 99.6 % when the floor was set); `adapters` is measured too, but most of its tests live in `app` and the persistence ones need `TEST_DATABASE_URL`, so it has no floor of its own. The report is in `<module>/target/site/jacoco/`.

## What is missing

The rest of the domain: regularizing a departure as not carried out and cancelling a departure (they cancel reservations: HU-OPS-006), the novelties and the personal emergency of a traveler, and the history of a departure (HU-OPS-008; `registerNovelty` and `listDepartureHistory` are in the contract and not offered); the check of the database in `/health`; `booked` is the seats held that `commercial-reservation` lists for the departure, which today only lists departures that have not started, so a departure in execution or finished answers 0 (declared gap with the owner) and the answers of scheduling, rescheduling and adding vehicles always say 0; `SalidaRescheduled`, `SalidaMarkedPendienteDeRegularizacion` and `ExtraordinaryCancellationRecorded` are published but no service consumes them yet (job 7 does not route the last one either); and `commercial-reservation` does not yet move its reservations when it receives the start and the end (HU-COM-010) nor block the ordinary changes of a reservation in execution (HU-COM-012), so what a regularization, a recording or a cancellation tells it does not reach them. The declared gaps are in the specs of `07-api/specs/operations-cost/multi-tour-operations-cost-api/`.

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
