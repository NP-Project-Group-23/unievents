# Step 3: session authorization and metrics integration

## Implemented behavior

The TCP dispatcher now treats every registered operation as protected except PING,
ECHO, REGISTER and LOGIN. Each protected call requires a valid request token; cached
connection identity is cleared before resolving the current session. Unknown commands
still return UNKNOWN_ACTION. A known expired token returns SESSION_EXPIRED and is
removed; missing, revoked or unknown tokens return UNAUTHENTICATED. Both expiry
responses require the client to log in again.

GET_METRICS and event mutations require ADMIN. Event mutation handlers remain
unimplemented, but the dispatcher policy is ready for their registration. Public
registration creates students; assigning ADMIN requires a live admin token even
when the supplied connection context claims to be an administrator. The socket
handler no longer caches identities from earlier login responses.

MetricsProtocolHandler implements the shared RequestHandler and is registered by
AuthHandlersConfig. It returns ResponseEnvelope with the existing snapshot fields,
an ISO UTC timestamp and the original requestId. The former MetricsEnvelope and
handle(String) API are removed. Counters come from the real bounded executor and
connection manager. Completed tasks are completed connection handlers, not request
counts; this change does not introduce per-request counters.

Both HTTP metrics aliases (/api/admin/metrics and /api/metrics), including their
events/dashboard endpoints, require ROLE_ADMIN. HTTP login loads the database role
into the Spring session; public HTTP registration always receives ROLE_STUDENT.
Anonymous access returns 401, student access 403. Existing browser sessions need
a fresh login to obtain the new authorities. TCP bearer tokens and HTTP session
cookies remain separate authentication mechanisms.

Roles are captured at session creation/login. After changing an account's role,
invalidate existing sessions and require login; live role-change propagation is
not implemented. There is no public first-admin bootstrap. Use the team's authorized
operator provisioning process. No shared database roles or data were changed here.

Unexpected dispatcher errors are logged server-side and return a generic error.
Login and registration no longer return exception details to clients.

## Verification

On 2026-11-19: BUILD SUCCESS; 113 tests, 0 failures, 0 errors, 0 skipped.
Run from backend:

```powershell
.\mvnw.cmd '-Dtest=*Tests,RequestDispatcherTest,FrameCodecTest,TcpServerClientTest,AuthHandlersIntegrationTest,T01ComprehensiveEdgeCaseTest,SessionManagerTest,StudentAuthServiceTest,UserRepositoryTest' test
```

New tests cover missing/invalid/revoked tokens with a forged cached admin identity,
known-expired sessions, and admin registration with a forged context. Real socket
tests exercise the Spring-registered metrics handler with anonymous, student and
admin requests, then revoke the token on the same connection. Real HTTP tests log
in through the CSRF-protected auth endpoint and verify all six metrics routes for
anonymous users, students and admins. Existing timeout tests now authenticate their
custom operation so they continue testing timeouts rather than permission rejection.

This run uses isolated H2 databases and ephemeral TCP/HTTP ports. JavaFX UI tests,
PostgreSQL validation and desktop end-to-end demonstration are not part of this run.
Next integration step: event administration, transactional seat generation, event
relationships and versioned migration execution.
