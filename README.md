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
  (see `ReplicaSharedStateTest`), not assumed.
- **Latency.** Persistent Object Store I/O (local disk, or CloudHub 2.0's managed backend over the
  network) is slower than the in-memory store this plugin used before BRU-54. Not measured here — this
  project has no CloudHub 2.0 access to benchmark the real network case against.

## Build and test

```
mvn clean verify
```

Runs the JUnit suite (state machine, error classification, storage, and the `execute` scope itself),
packages the plugin, and gates on line coverage.

## Licence

[MIT](LICENSE)
