# Appointment Scheduler — bounded service slice

Java 17, Spring Boot 3.x, Maven, PostgreSQL 15+, Spring Data JPA, Flyway. This project implements booking and cancellation only. PostgreSQL's partial GiST exclusion constraint prevents overlapping **ACTIVE** appointments for the same provider, using half-open `[start, end)` intervals.

## Prerequisites

- JDK 17 and Maven 3.9+ on `PATH`
- Docker Engine / Docker Desktop with Docker Compose v2
- Docker daemon running; PostgreSQL's configured host port available (default `5432`)

## Configure (environment variables only)

Set `DB_USERNAME` and `DB_PASSWORD` in your **local shell**. Do not commit values to files. `DB_NAME` defaults to `scheduler`, `DB_HOST` to `localhost`, and `DB_PORT` to `5432`.

For bash/zsh:

```bash
export DB_USERNAME='scheduler_user'
export DB_PASSWORD='<choose-a-local-password>'
export DB_NAME='scheduler'
export DB_HOST='localhost'
export DB_PORT='5432'
```

For PowerShell:

```powershell
$env:DB_USERNAME = 'scheduler_user'
$env:DB_PASSWORD = '<choose-a-local-password>'
$env:DB_NAME = 'scheduler'
$env:DB_HOST = 'localhost'
$env:DB_PORT = '5432'
```

Replace the placeholder password locally. It is not an application secret embedded in the repository. For a fresh database volume, Docker Compose creates the database/user from these variables; changing credentials later does **not** reinitialize an existing volume.

## Start and run

From the project root (the directory containing `pom.xml`):

```bash
docker compose up -d
mvn spring-boot:run
```

Check container state:

```bash
docker compose ps
```

On application startup, Flyway applies `src/main/resources/db/migration/V1__create_appointments.sql`, including `btree_gist`, the table, checks, and the ACTIVE-overlap exclusion constraint. The database account used for migration must be permitted to create that extension. Hibernate uses `ddl-auto: validate`.

To stop the database without deleting stored bookings:

```bash
docker compose down
```

**Do not use** `docker compose down -v` unless intentionally deleting the local database volume.

## API quick check

Book an appointment (IDs and times are illustrative; `providerId` must be a canonical UUID):

```bash
curl -i -X POST http://localhost:8080/api/v1/appointments \
  -H 'Content-Type: application/json' \
  -d '{"providerId":"11111111-1111-4111-8111-111111111111","startTime":"2026-11-01T10:00:00Z","endTime":"2026-11-01T11:00:00Z"}'
```

Expected success: `201 Created`, an appointment response body, and `Location: /api/v1/appointments/{id}`. Retain the returned `id` to cancel:

```bash
curl -i -X POST http://localhost:8080/api/v1/appointments/<returned-appointment-id>/cancel
```

Expected success: `200 OK` and a response body with `status: "CANCELLED"`. Same-provider overlapping ACTIVE bookings are rejected with `409 APPOINTMENT_CONFLICT`; adjacent bookings and bookings for other providers are permitted. Repeated cancellation returns `409 ALREADY_CANCELLED`; unknown IDs return `404 APPOINTMENT_NOT_FOUND`. Errors use `application/problem+json` with a `code` extension.

## Exact test commands

Run from the project root with Docker running if tests use Testcontainers:

```bash
mvn clean test
mvn clean verify
```

`mvn clean test` executes Maven's test phase. `mvn clean verify` also executes tests in the lifecycle before packaging/verification. **Current status:** the reviewed stage-2 archive contains **no automated test classes**, so these commands alone are *not* evidence that the booking invariant or concurrency behavior has been tested. Integration tests using Testcontainers must be added and run for acceptance; H1–H4 are approved findings but remain unresolved. Maven/Docker end-to-end verification was not performed when these files were prepared.

## Documentation

- [`docs/DESIGN.md`](docs/DESIGN.md) — service boundaries, API/data model, SQL invariant, alternatives, Mermaid diagrams
- [`docs/ASSUMPTIONS.md`](docs/ASSUMPTIONS.md) — compact invariant and assumptions
- [`docs/LIMITATIONS.md`](docs/LIMITATIONS.md) — current limitations and bounded follow-up risks
- [`docs/AI_USAGE_RECORD.md`](docs/AI_USAGE_RECORD.md) — empty template for the candidate's actual AI assistance log

This is not a deployed service; it intentionally excludes provider management, auth, recurring schedules, notifications, and UI work.
