# mule4-circuit-breaker

A circuit breaker plugin for Mule 4, written from scratch. It opens the circuit when a backend keeps failing on a sliding-window failure rate. State is kept in the Mule runtime's own persistent Object Store, the same mechanism CloudHub 2.0 uses to share data across an app's replicas — so a circuit opened by one replica is visible to the others, with the limits documented below.

## Layout

```
src/main/java/.../circuitbreaker/
├── api/store/       CircuitStateStore port, CircuitSnapshot, SlidingWindow — the public extension point
└── internal/
    ├── CircuitBreakerExtension.java       @Extension entry point
    ├── CircuitBreakerOperations.java      the single execute scope
    ├── state/        pure CLOSED/OPEN/HALF_OPEN decision logic
    ├── classify/      error-type classification (infra failure vs business error)
    ├── error/         CIRCUIT-BREAKER:OPEN typed error
    └── store/         ObjectStoreCircuitStateStore, the persistent Object Store adapter
src/test/java/         JUnit 5 unit tests — state machine, classification, storage (including a
                        replica-sharing test against the real Mule runtime mechanism), and the
                        execute scope itself, exercised directly as a plain Java method
src/test/munit/        MUnit DSL suite (not run by `mvn verify` in this environment — see pom.xml)
```

## Use

```xml
<circuit-breaker:execute circuitBreakerKey="my-service" failureErrorTypes="HTTP:TIMEOUT,HTTP:CONNECTIVITY">
    <http:request config-ref="my-service-config" path="/orders" method="GET" />
</circuit-breaker:execute>
```

`circuitBreakerKey` identifies the circuit; calls sharing a key share circuit state. Every other parameter
(`windowSize`, `minimumCalls`, `failureRateThreshold`, `resetTimeout`, `permittedCallsInHalfOpen`,
`successThreshold`, `failureErrorTypes`) has a sensible default — see the operation's own parameter
descriptions in Studio/the generated docs for details. A blocked call raises `CIRCUIT-BREAKER:OPEN`.

## Limits of shared state

State is stored in a *persistent* Object Store (`ObjectStoreCircuitStateStore`). Locally/standalone that
means the runtime's working directory on disk; on CloudHub 2.0 the runtime backs the same setting with its
own managed, replica-shared implementation instead — no code in this plugin changes between the two, only
what the runtime plugs in underneath. That gives real cross-replica visibility, with two concrete limits:

- **No cross-replica lock.** The lock this plugin takes (`LockFactory`) only serializes reads/writes to a
  circuit key *within one replica*. Two replicas writing to the same `circuitBreakerKey` at close to the
  same time race at the storage level, with no cross-process coordination — last write wins, and a write
  from one replica can be silently overwritten by another's.
- **No coordination on a circuit's first write, ever.** The very first time a given `circuitBreakerKey` is
  persisted anywhere, if two replicas both do so before either has anything on disk yet, the Mule runtime's
  persistent Object Store implementation can let each one create its own separate backing location for that
  key — they silently diverge instead of erroring. This is runtime-internal behaviour this plugin cannot
  coordinate around; it only matters for a circuit's very first write, not for calls afterward. Verified by
  reading the runtime's own `PartitionedPersistentObjectStore`/`PersistentObjectStorePartition` classes
  (see `ReplicaSharedStateTest`), not assumed. On a real CloudHub 2.0 deployment (2 replicas), sending two
  concurrent first requests for a brand-new key produced no visible error, but the circuit needed more real
  failures than configured to open afterward (8 instead of 5) — consistent with this limit, though not
  provable from outside a pod; see
  [`mule4-circuit-breaker-demo-app`'s evidence](https://github.com/brunosouzas/mule4-circuit-breaker-demo-app/blob/2d69847/evidence/BRU-58/first-write-concurrency.md).
- **Latency.** Persistent Object Store I/O (local disk, or CloudHub 2.0's managed backend over the
  network) is slower than the in-memory store this plugin used before BRU-54. Measured on a real CloudHub
  2.0 deployment (2 replicas, 0.1 vCore each): **+5.6 ms median** (+5.0 ms mean, +2.3 ms p95) per
  `circuit-breaker:execute` call, isolated by comparing an endpoint that goes through the circuit breaker
  against an identical one that doesn't. That's the cost of 4 Object Store operations
  (`CircuitBreakerOperations.execute()` calls `stateStore.update()` twice per invocation, each doing a
  locked read + write) against CloudHub 2.0's managed backend, under otherwise idle load — not a number for
  the Object Store's behaviour under concurrent load from many replicas at once, which this measurement
  didn't exercise. Method, raw samples and cross-replica proof:
  [`mule4-circuit-breaker-demo-app`'s evidence](https://github.com/brunosouzas/mule4-circuit-breaker-demo-app/blob/2d69847/evidence/BRU-58/latency-summary.md)
  (BRU-58).

## Build and test

```
mvn clean verify
```

Runs the JUnit suite (state machine, error classification, storage, and the `execute` scope itself),
packages the plugin, and gates on line coverage.

## Exchange coordinates

Published to the Anypoint Exchange of a personal Anypoint Developer Trial organization:

```xml
<dependency>
    <groupId>c17ce335-be76-4fd0-87e2-130837d2c65a</groupId>
    <artifactId>mule4-circuit-breaker</artifactId>
    <version>1.0.0</version>
    <classifier>mule-plugin</classifier>
</dependency>
```

Publishing is reproducible and pipeline-only, never from a developer's machine: `azure-pipelines.yml`
extends the [`azure-devops-mulesoft-pipelines`](https://github.com/brunosouzas/azure-devops-mulesoft-pipelines)
GitFlow templates with `deploy: false` (this plugin has nothing to deploy to CloudHub 2.0). `develop`
publishes the current `-SNAPSHOT` to Exchange; `main` runs a Maven release (tags `vx.y.z`, publishes the
release, moves `main` to the next `-SNAPSHOT`). Credentials live only in the pipeline's Azure DevOps
variable group, never in this repository.

## Retry-only vs circuit breaker: a reproducible demo

`RetryVsCircuitBreakerDemoTest` (`src/test/java/.../circuitbreaker/internal/`) is a self-contained,
deterministic comparison for anyone evaluating whether this plugin is worth adding on top of a plain retry
loop. It simulates 20 requests arriving while a backend is down (`HTTP:TIMEOUT` on every call), run two
ways:

- **Retry only**: each request calls the backend directly, retrying up to 3 times with a fixed delay —
  every request exhausts its retries against the still-broken backend.
- **Retry + circuit breaker**: each request's call is wrapped in `circuit-breaker:execute` (called directly
  as a plain Java method, same rationale as `CircuitBreakerOperationsTest` — see the environment note in
  `pom.xml`). The first 5 requests feed the sliding window and trip the circuit; the remaining 15 get
  `CIRCUIT-BREAKER:OPEN` immediately, without the backend ever being called again.

There is no standalone Mule runtime in this environment (the same limitation documented in `pom.xml` for
the disabled MUnit execution), so this demo runs as a plain JUnit test instead of a deployed Mule
application — no code under `src/main` is involved beyond the `execute` scope itself.

Reproduce it with:

```
mvn test -Dtest=RetryVsCircuitBreakerDemoTest
```

Representative output from an actual run:

```
scenario=RETRY_ONLY request=1/20 outcome=FAILURE backendInvocationsSoFar=3
...
scenario=RETRY_ONLY summary backendInvocations=60 elapsedMillis=1929 success=0 failure=20
scenario=CIRCUIT_BREAKER request=6/20 outcome=CIRCUIT_OPEN backendInvocationsSoFar=5
...
scenario=CIRCUIT_BREAKER summary backendInvocations=5 elapsedMillis=139 success=0 failure=5 circuitOpen=15
COMPARISON backendInvocations retryOnly=60 circuitBreaker=5 ; elapsedMillis retryOnly=1929 circuitBreaker=139
```

The test doesn't rely on eyeballing that output: it asserts the contrast mechanically — total backend
invocations and total elapsed time for the circuit-breaker run are both strictly lower than for the
retry-only run — so a green `mvn test` run is itself the evidence.

## Licence

[MIT](LICENSE)
