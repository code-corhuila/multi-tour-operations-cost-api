# multi-tour-operations-cost-api

> The service of the operations-cost domain: the departures of the tours, their execution and their costs

Part of the **Multi Tour** distributed system — team `multi-tour`, Grupo 2.
Governance and documentation live in [`multi-tour-docs`](https://github.com/code-corhuila/multi-tour-docs).

## What it is

A Java 21 / Spring Boot 3.5 service in three Maven modules: `core` (the domain and its ports, no framework), `adapters` (HTTP and PostgreSQL) and `app` (the composition root). It will own the departures of the tours, starting with scheduling them (HU-OPS-001). Today the three modules are empty: this is the base the first operations are built on.

## How to start it

Not yet: there is no image or composition in this repository. The base of the service, with its image and its place in the platform, comes with the next tasks of spec 001 (`07-api/specs/operations-cost/multi-tour-operations-cost-api/` in `multi-tour-docs`). The variables it will read are listed in `.env.example`.

## Where the data is

In the schema `operations_cost` of the single PostgreSQL instance of `multi-tour-infra-postgres`, with the user `operations_cost_app`. The schema is built by `multi-tour-operations-cost-db`; this repository holds no schema or migration.

## How to test it

`mvn -B verify` with Java 21. It runs on every pull request to `develop` and `qa` (`ci.yml`). The code coverage floor (JaCoCo) is added with the first code.

## What is missing

Everything but the structure: the HTTP shell, the departures, their events. See the specs in `07-api/specs/operations-cost/multi-tour-operations-cost-api/`.

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
