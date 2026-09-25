package com.brunosouzas.mule.extension.circuitbreaker.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.brunosouzas.mule.extension.circuitbreaker.api.store.CircuitSnapshot;
import com.brunosouzas.mule.extension.circuitbreaker.api.store.CircuitStateStore;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mule.runtime.api.exception.TypedException;
import org.mule.runtime.api.message.ErrorType;
import org.mule.runtime.extension.api.runtime.operation.Result;
import org.mule.runtime.extension.api.runtime.process.CompletionCallback;
import org.mule.runtime.extension.api.runtime.route.Chain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reproducible evidence for BRU-55: compares a plain retry loop against {@code circuit-breaker:execute}
 * when the backend they call is down, and proves the difference with counters instead of asking a reader
 * to eyeball log output. Exercises {@link CircuitBreakerOperations#execute} directly as a plain Java
 * method — no Mule runtime needed, same rationale as {@link CircuitBreakerOperationsTest} (see the
 * environment note on the disabled munit-extensions-maven-plugin execution in pom.xml). It lives in this
 * package, rather than its own, because {@code stateStore} is package-private by design (see that field's
 * own comment) precisely so tests can inject a fake store without a real Mule runtime.
 *
 * <p>Twenty simulated requests arrive while the backend always fails with {@code HTTP:TIMEOUT}. "Retry
 * only" retries every request directly against the backend up to a fixed attempt count, so it keeps
 * hammering a backend it has no way of knowing is still down. "With circuit breaker" wraps the same calls
 * in {@code execute}: the first {@code minimumCalls} requests trip the circuit, and every request after
 * that is rejected with {@code CIRCUIT-BREAKER:OPEN} before the backend is ever invoked again.
 */
class RetryVsCircuitBreakerDemoTest {

  private static final Logger log = LoggerFactory.getLogger(RetryVsCircuitBreakerDemoTest.class);

  private static final int REQUEST_COUNT = 20;
  private static final long BACKEND_LATENCY_MILLIS = 20;

  private static final int RETRY_MAX_ATTEMPTS = 3;
  private static final long RETRY_DELAY_MILLIS = 10;

  private static final int WINDOW_SIZE = 5;
  private static final int MINIMUM_CALLS = 5;
  private static final int FAILURE_RATE_THRESHOLD = 50;
  // Far larger than this scenario's total wall-clock time (~100ms), so the circuit never has a chance to
  // leave OPEN for HALF_OPEN during the run — removes any timing race with the reset timeout entirely.
  private static final long RESET_TIMEOUT_MILLIS = 60_000L;
  private static final int PERMITTED_CALLS_IN_HALF_OPEN = 1;
  private static final int SUCCESS_THRESHOLD = 1;
  private static final String FAILURE_ERROR_TYPES = "HTTP:TIMEOUT";
  private static final String CIRCUIT_KEY = "demo-backend";

  @Test
  void circuitBreakerCallsBackendFarLessAndFinishesFasterThanRetryOnly() {
    ScenarioResult retryOnly = runRetryOnlyScenario();
    ScenarioResult breaker = runCircuitBreakerScenario();

    log.info("COMPARISON backendInvocations retryOnly={} circuitBreaker={} ; elapsedMillis retryOnly={} circuitBreaker={}",
        retryOnly.backendInvocations, breaker.backendInvocations, retryOnly.elapsedMillis, breaker.elapsedMillis);

    assertEquals(REQUEST_COUNT * RETRY_MAX_ATTEMPTS, retryOnly.backendInvocations);
    assertEquals(REQUEST_COUNT, retryOnly.failureCount);
    assertEquals(0, retryOnly.circuitOpenCount);

    assertEquals(MINIMUM_CALLS, breaker.backendInvocations);
    assertEquals(MINIMUM_CALLS, breaker.failureCount);
    assertEquals(REQUEST_COUNT - MINIMUM_CALLS, breaker.circuitOpenCount);

    assertTrue(breaker.backendInvocations < retryOnly.backendInvocations,
        "circuit breaker should call the backend far less than retry-only");
    assertTrue(breaker.elapsedMillis < retryOnly.elapsedMillis,
        "circuit breaker should finish far faster than retry-only");
  }

  private static ScenarioResult runRetryOnlyScenario() {
    SimulatedBackend backend = new SimulatedBackend();
    int successCount = 0;
    int failureCount = 0;
    long start = System.currentTimeMillis();

    for (int request = 1; request <= REQUEST_COUNT; request++) {
      Outcome outcome = Outcome.FAILURE;
      for (int attempt = 1; attempt <= RETRY_MAX_ATTEMPTS; attempt++) {
        try {
          backend.call();
          outcome = Outcome.SUCCESS;
          break;
        } catch (Throwable ignored) {
          if (attempt < RETRY_MAX_ATTEMPTS) {
            sleep(RETRY_DELAY_MILLIS);
          }
        }
      }
      if (outcome == Outcome.SUCCESS) {
        successCount++;
      } else {
        failureCount++;
      }
      log.info("scenario=RETRY_ONLY request={}/{} outcome={} backendInvocationsSoFar={}",
          request, REQUEST_COUNT, outcome, backend.getInvocationCount());
    }

    long elapsed = System.currentTimeMillis() - start;
    ScenarioResult result = new ScenarioResult(
        backend.getInvocationCount(), elapsed, successCount, failureCount, 0);
    log.info("scenario=RETRY_ONLY summary backendInvocations={} elapsedMillis={} success={} failure={}",
        result.backendInvocations, result.elapsedMillis, result.successCount, result.failureCount);
    return result;
  }

  private static ScenarioResult runCircuitBreakerScenario() {
    SimulatedBackend backend = new SimulatedBackend();
    CircuitBreakerOperations operations = new CircuitBreakerOperations();
    operations.stateStore = new InMemoryCircuitStateStore();

    int successCount = 0;
    int failureCount = 0;
    int circuitOpenCount = 0;
    long start = System.currentTimeMillis();

    for (int request = 1; request <= REQUEST_COUNT; request++) {
      BackendChain chain = new BackendChain(backend);
      CapturingCallback callback = new CapturingCallback();

      operations.execute(CIRCUIT_KEY, WINDOW_SIZE, MINIMUM_CALLS, FAILURE_RATE_THRESHOLD, RESET_TIMEOUT_MILLIS,
          PERMITTED_CALLS_IN_HALF_OPEN, SUCCESS_THRESHOLD, FAILURE_ERROR_TYPES, chain, callback);

      Outcome outcome;
      if (!chain.invoked) {
        outcome = Outcome.CIRCUIT_OPEN;
        circuitOpenCount++;
      } else if (callback.errored) {
        outcome = Outcome.FAILURE;
        failureCount++;
      } else {
        outcome = Outcome.SUCCESS;
        successCount++;
      }
      log.info("scenario=CIRCUIT_BREAKER request={}/{} outcome={} backendInvocationsSoFar={}",
          request, REQUEST_COUNT, outcome, backend.getInvocationCount());
    }

    long elapsed = System.currentTimeMillis() - start;
    ScenarioResult result = new ScenarioResult(
        backend.getInvocationCount(), elapsed, successCount, failureCount, circuitOpenCount);
    log.info(
        "scenario=CIRCUIT_BREAKER summary backendInvocations={} elapsedMillis={} success={} failure={} circuitOpen={}",
        result.backendInvocations, result.elapsedMillis, result.successCount, result.failureCount,
        result.circuitOpenCount);
    return result;
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private enum Outcome {
    SUCCESS, FAILURE, CIRCUIT_OPEN
  }

  /** Aggregate metrics for one scenario's run over all {@link #REQUEST_COUNT} simulated requests. */
  private static final class ScenarioResult {

    final int backendInvocations;
    final long elapsedMillis;
    final int successCount;
    final int failureCount;
    final int circuitOpenCount;

    ScenarioResult(int backendInvocations, long elapsedMillis, int successCount, int failureCount,
        int circuitOpenCount) {
      this.backendInvocations = backendInvocations;
      this.elapsedMillis = elapsedMillis;
      this.successCount = successCount;
      this.failureCount = failureCount;
      this.circuitOpenCount = circuitOpenCount;
    }
  }

  /** A fake remote dependency that is always down: every call sleeps then fails with {@code HTTP:TIMEOUT}. */
  private static final class SimulatedBackend {

    private int invocationCount;

    Object call() throws Throwable {
      invocationCount++;
      Thread.sleep(BACKEND_LATENCY_MILLIS);
      throw new TypedException(new RuntimeException("simulated backend timeout"),
          new SimpleErrorType("HTTP", "TIMEOUT"));
    }

    int getInvocationCount() {
      return invocationCount;
    }
  }

  /** A {@link Chain} that actually invokes the given backend, unlike {@link CircuitBreakerOperationsTest}'s
   *  pre-programmed {@code FakeChain}. */
  private static final class BackendChain implements Chain {

    private final SimulatedBackend backend;
    boolean invoked;

    BackendChain(SimulatedBackend backend) {
      this.backend = backend;
    }

    @Override
    public void process(Consumer<Result> onSuccess, BiConsumer<Throwable, Result> onError) {
      invoked = true;
      try {
        Object output = backend.call();
        onSuccess.accept(Result.builder().output(output).build());
      } catch (Throwable t) {
        onError.accept(t, Result.builder().build());
      }
    }

    @Override
    public void process(Object payload, Object attributes, Consumer<Result> onSuccess,
        BiConsumer<Throwable, Result> onError) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void process(Result input, Consumer<Result> onSuccess, BiConsumer<Throwable, Result> onError) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class CapturingCallback implements CompletionCallback<Object, Object> {

    boolean errored;

    @Override
    public void success(Result<Object, Object> result) {
    }

    @Override
    public void error(Throwable throwable) {
      this.errored = true;
    }
  }

  /** Minimal in-memory {@link CircuitStateStore}: single-threaded, no locking needed for this demo. */
  private static final class InMemoryCircuitStateStore implements CircuitStateStore {

    private final Map<String, CircuitSnapshot> data = new HashMap<>();

    @Override
    public <T> T update(String circuitKey, StateTransition<T> transition) {
      Update<T> update = transition.apply(data.get(circuitKey));
      data.put(circuitKey, update.getNewSnapshot());
      return update.getResult();
    }
  }

  private static final class SimpleErrorType implements ErrorType {

    private final String namespace;
    private final String identifier;

    SimpleErrorType(String namespace, String identifier) {
      this.namespace = namespace;
      this.identifier = identifier;
    }

    @Override
    public String getIdentifier() {
      return identifier;
    }

    @Override
    public String getNamespace() {
      return namespace;
    }

    @Override
    public ErrorType getParentErrorType() {
      return null;
    }
  }
}
