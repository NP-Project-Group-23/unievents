# Stage 4: no-Docker release hardening

## Implemented

- The combined JavaFX launcher now discovers the actual TCP server port from the running Spring context instead of assuming port `9090`.
- Partial startup failures close the TCP client and Spring context before the application exits.
- Local runtime configuration supports `SERVER_PORT`, `TCP_PORT`, `TCP_BIND_ADDRESS`, and `LOCAL_DB_PATH` environment overrides.
- `LocalRuntimeSmokeTests` starts the local profile with isolated storage and random HTTP/TCP ports, applies all Flyway migrations, verifies TCP readiness, and verifies local administrator bootstrap.
- `scripts/build-local-release.ps1` runs the complete test suite, performs a clean no-Docker package build, and reports artifact size, SHA-256, and the launch command.

## Build and verify

From the repository root:

```powershell
.\scripts\build-local-release.ps1
```

To rebuild only after an already successful test run:

```powershell
.\scripts\build-local-release.ps1 -SkipTests
```

The release artifact is written to:

```text
backend/target/unievents-backend-0.0.1-SNAPSHOT.jar
```

## Optional local overrides

```powershell
$env:SERVER_PORT = '8081'
$env:TCP_PORT = '9091'
$env:LOCAL_DB_PATH = './data/demo-copy'
java -jar .\backend\target\unievents-backend-0.0.1-SNAPSHOT.jar
```

The desktop connects to the configured TCP server automatically.

## Verification evidence

Verified on 2026-11-19:

- 145 tests passed, 0 failures, 0 errors, 0 skipped.
- All 90 production and 35 test sources compiled in a clean build.
- The Spring Boot desktop JAR packaged successfully without Docker.
- The release verifier generated a SHA-256 checksum for the artifact.

The two Maven metadata `401` warnings concern optional Flyway vendor repositories and did not affect dependency resolution, tests, or packaging.

