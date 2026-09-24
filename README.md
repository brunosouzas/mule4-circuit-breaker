# mule4-circuit-breaker

A circuit breaker plugin for Mule 4, written from scratch. It opens the circuit when a backend keeps failing on a sliding-window failure rate, and it is designed so that a later, separate adapter can share that state across replicas (for example on CloudHub 2.0) without changing any decision logic — this repository's own state store is single-node for now.

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
    └── store/         ObjectStoreCircuitStateStore, the default single-node adapter
src/test/java/         JUnit 5 unit tests — state machine, classification, storage, and the execute
                        scope itself, exercised directly as a plain Java method
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

## Build and test

```
mvn clean verify
```

Runs the JUnit suite (state machine, error classification, storage, and the `execute` scope itself),
packages the plugin, and gates on line coverage.

## Licence

[MIT](LICENSE)
