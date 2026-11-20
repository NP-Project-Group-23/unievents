# Member 3: PostgreSQL concurrency verification

## Purpose

`PostgresBookingConcurrencyTests` verifies the production reservation strategy on
real PostgreSQL rather than H2 compatibility mode. Ten threads contend for one seat
(one winner and nine conflicts), then ten threads reserve independent seats (ten
winners). The normal test suite leaves this class disabled.

## Isolation and safety

The test creates a random schema named `m3_booking_<uuid>`, directs Flyway, Hibernate,
and Hikari to that schema, and drops exactly that validated schema afterward. It does
not truncate or modify the shared `public` schema. Use a disposable Neon development
branch anyway; never use the production branch for load or concurrency testing.

Use a direct Neon connection string (hostname without `-pooler`) because Flyway
migrations and schema-level setup require a direct session.

## Run explicitly

From `backend` in PowerShell:

```powershell
$env:BOOKING_TEST_DB_URL = 'jdbc:postgresql://DIRECT-TEST-HOST/neondb?sslmode=require'
$env:BOOKING_TEST_DB_USERNAME = 'test_owner'
$env:BOOKING_TEST_DB_PASSWORD = 'test_password'
.\mvnw.cmd '-Dbooking.postgres.test=true' '-Dtest=PostgresBookingConcurrencyTests' test
```

Successful completion is evidence only for the supplied database/branch and run.
Record the command, branch name, isolation level, test count, and Maven result under
`docs/testing/`. Do not claim PostgreSQL verification from a skipped normal run.
