# Limitations and follow-up risks

Only the conflict-safe booking/cancellation slice is in scope. The following are **not claims of completed implementation work**.

| Area | Current limitation or risk | Bounded follow-up / decision |
| --- | --- | --- |
| **Verification** | The reviewed project has no automated test classes. Native overlap SQL parameter binding, concurrent inserts, and optimistic-lock error mapping have not been exercised against PostgreSQL/Testcontainers. | **Accepted H1–H4:** add focused real-DB integration/concurrency tests; verify persistence/new-entity handling and constraint translation before signoff. |
| **Timestamp parsing** | Some syntactically matching ISO strings can be normalized by `Instant.parse` (for example, `24:00:00Z` and leap-second forms). | **Accepted M1:** reject normalized/noncanonical input rather than silently changing the requested instant. |
| **Audit precision** | `Instant.now()` may contain nanoseconds, but `timestamptz(6)` stores microseconds; response and subsequent DB read can differ. | **Accepted M2:** normalize audit timestamps consistently and test round trips. |
| **Booking retries / idempotency** | If a booking commits but its response is lost, replaying the same POST can return `409` rather than the original booking ID. There is no client idempotency key, deduplication table, or request fingerprint. | **Accepted M5 (documentation):** document uncertainty after network timeout; adding an idempotency-key contract is a separate design decision, **not implemented**. Avoid automatic retries of a confirmed overlap. |
| **Rate limiting** | No request throttling or abuse controls. | Deferred/outside this slice; address at gateway or API boundary if required later. |
| **Pagination/search** | No listing endpoint or pagination; only book and cancel are available. | Outside this slice; add only when a consumer needs searching/listing. |
| **Observability** | No dedicated booking-conflict metrics, tracing, or structured audit/event pipeline. | Follow-up: instrument success, exclusion violations, latency, and cancellation outcomes without leaking identifiers/secrets. |
| **Provider validation** | A well-formed provider UUID need not exist in another system. | Provider management is explicitly out of scope. |
| **Cancellation vs. booking race** | An early overlap check can return 409 while a concurrent cancellation is about to free the same interval. Safety is maintained, but availability can change immediately afterward. | **Deferred M4:** document behavior; don't add cross-provider locks or retry logic without need. |
| **Transient failures** | No bounded retry policy for deadlocks, connection loss, or transient serialization errors. | **Deferred M7:** monitor/define retry boundaries if the system evolves. |
| **Duration policy** | Any positive interval, even spanning years, is allowed; historical appointments are accepted. | **Deferred M3:** do not add a duration or future-only rule without product requirements. |
| **UUID collision semantics** | UUIDv4 collisions are extremely unlikely; interactions with entity newness/merge still need verification. | **Deferred M6** except for the **accepted H3** JPA persistence verification. |
| **Audit ordering** | No DB check enforces `cancelled_at >= created_at` across clock skew. | **Deferred M8:** record the risk; no new constraint now. |
| **Portability & index cost** | `btree_gist` and GiST exclusion are PostgreSQL-specific; a separate ACTIVE B-tree index adds write/storage overhead. | Accepted tradeoff; index measurement and portability work deferred (L4). |
| **HTTP affordances** | Booking returns a `Location` for a resource without a GET endpoint; repeated cancellation reports a 409 rather than HTTP-level success. | Intentionally bounded API; GET and richer clients are out of scope. |

**Release gate for the bounded feature:** demonstrate Flyway startup and tests proving exactly one winner under same-provider concurrent overlap, two winners for different providers, adjacency, cancelled-slot reuse, invalid-input non-mutation, and correct 404/409 mapping. As of document creation, this evidence is still outstanding.
