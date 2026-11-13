# multi-tour-operations-cost-api

> The service of the operations-cost domain: the departures of the tours, their execution and their costs

Part of the **Multi Tour** distributed system — team `multi-tour`, Grupo 2.
Governance and documentation live in [`multi-tour-docs`](https://github.com/code-corhuila/multi-tour-docs).

## What it is

A Java 21 / Spring Boot 3.5 service in three Maven modules: `core` (the domain and its ports, no framework), `adapters` (HTTP) and `app` (the composition root). It will own the departures of the tours, starting with scheduling them (HU-OPS-001). Today it is the base the first operations are built on: `GET /health` without a token, the validation of the RS256 token by the service itself, one error body for every failure, the correlation id, the limits of the server and of the database pool, and a 16 KB limit on a body.

## How to start it

The image is built and run by the composition of the platform: `multi-tour-infra-postgres` includes `deploy/compose.yml`, which defines the service `operations-cost-api` on the network `platform`. It exposes 8084 and publishes no port: only the gateway is reachable from the host. It needs `JWT_PUBLIC_KEY` (or `JWT_PUBLIC_KEY_FILE`), the public key of the security microservice, and `DATABASE_URL` (a `jdbc:postgresql://` address) with `DATABASE_USER` and `DATABASE_PASSWORD` of the user `operations_cost_app`; without the key or the address the service stops at start and says which one. To try the image alone, `sh deploy/tests/deploy-check.sh` builds it, starts it with a key made on the spot and asks it for `/health` and for a protected route; it needs Docker and openssl.

## Where the data is

In the schema `operations_cost` of the single PostgreSQL instance of `multi-tour-infra-postgres`, with the user `operations_cost_app`. The schema is built by `multi-tour-operations-cost-db`; this repository holds no schema or migration.

## How to test it

`mvn -B verify` with Java 21, and `sh deploy/tests/deploy-check.sh` for the image. Both run on every pull request to `develop` and `qa` (`ci.yml`). The code coverage floor (JaCoCo) is added with the first business code.

## What is missing

The departures and their events, and the check of the database in `/health`. See the specs in `07-api/specs/operations-cost/multi-tour-operations-cost-api/`.

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
