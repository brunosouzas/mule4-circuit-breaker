package com.brunosouzas.mule.extension.circuitbreaker.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.brunosouzas.mule.extension.circuitbreaker.api.store.CircuitSnapshot;
import com.brunosouzas.mule.extension.circuitbreaker.api.store.CircuitState;
import com.brunosouzas.mule.extension.circuitbreaker.api.store.CircuitStateStore;
import com.brunosouzas.mule.extension.circuitbreaker.api.store.SlidingWindow;
import com.brunosouzas.mule.extension.circuitbreaker.internal.error.CircuitBreakerError;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mule.runtime.api.exception.TypedException;
import org.mule.runtime.api.message.ErrorType;
import org.mule.runtime.extension.api.exception.ModuleException;
import org.mule.runtime.extension.api.runtime.operation.Result;
import org.mule.runtime.extension.api.runtime.process.CompletionCallback;
import org.mule.runtime.extension.api.runtime.route.Chain;

/**
 * Exercises the {@code execute} scope directly as a plain Java method — no Mule runtime needed, since
 * {@link Chain} and {@link CompletionCallback} are just interfaces. This is the DSL/wiring-level coverage
 * MUnit would otherwise provide (see the environment note on the disabled munit-extensions-maven-plugin
 * execution in pom.xml).
 */
class CircuitBreakerOperationsTest {

  @Test
  void successfulCallPassesPayloadThroughAndRecordsSuccess() {
    CircuitBreakerOperations operations = newOperations();
    FakeChain chain = FakeChain.success("ok");
    CapturingCallback callback = new CapturingCallback();

    operations.execute("k1", 4, 4, 50, 30000, 1, 1, "", chain, callback);

    assertEquals("ok", callback.successResult.getOutput());
    assertFalse(callback.errored);
  }

  @Test
  void blockedCallRaisesCircuitBreakerOpenAndNeverInvokesTheChain() {
    CircuitBreakerOperations operations = newOperations();
    CircuitSnapshot open = new CircuitSnapshot(CircuitState.OPEN, SlidingWindow.empty(4),
        System.currentTimeMillis(), 0, 0);
    operations.stateStore.<Void>update("k1", current -> new CircuitStateStore.Update<>(open, null));

    FakeChain chain = FakeChain.success("should not run");
    CapturingCallback callback = new CapturingCallback();

    operations.execute("k1", 4, 4, 50, 30000, 1, 1, "", chain, callback);

    assertTrue(callback.errored);
    assertFalse(chain.invoked);
    assertTrue(callback.error instanceof ModuleException);
    assertEquals(CircuitBreakerError.OPEN, ((ModuleException) callback.error).getType());
  }

  @Test
  void classifiedFailureCountsTowardOpeningTheCircuit() {
    CircuitBreakerOperations operations = newOperations();
    Throwable timeout = new TypedException(new RuntimeException("boom"), new FakeErrorType("HTTP", "TIMEOUT"));
    FakeChain chain = FakeChain.failure(timeout);
    CapturingCallback callback = new CapturingCallback();

    // windowSize=1, minimumCalls=1, failureRateThreshold=0: a single classified failure opens it.
    operations.execute("k2", 1, 1, 0, 30000, 1, 1, "HTTP:TIMEOUT", chain, callback);

    assertTrue(callback.errored);
    assertEquals(timeout, callback.error);
    CircuitSnapshot after = operations.stateStore.update("k2", current -> new CircuitStateStore.Update<>(current, current));
    assertEquals(CircuitState.OPEN, after.getState());
  }

  @Test
  void unclassifiedFailureNeverOpensTheCircuit() {
    CircuitBreakerOperations operations = newOperations();
    Throwable validation = new TypedException(new RuntimeException("bad payload"), new FakeErrorType("APP", "VALIDATION"));
    FakeChain chain = FakeChain.failure(validation);
    CapturingCallback callback = new CapturingCallback();

    operations.execute("k3", 1, 1, 0, 30000, 1, 1, "HTTP:TIMEOUT", chain, callback);

    assertTrue(callback.errored);
    CircuitSnapshot after = operations.stateStore.update("k3", current -> new CircuitStateStore.Update<>(current, current));
    assertEquals(CircuitState.CLOSED, after.getState());
  }

  private static CircuitBreakerOperations newOperations() {
    CircuitBreakerOperations operations = new CircuitBreakerOperations();
    operations.stateStore = new InMemoryCircuitStateStore();
    return operations;
  }

  /** Minimal in-memory {@link CircuitStateStore}: single-threaded, no locking needed for these tests. */
  private static final class InMemoryCircuitStateStore implements CircuitStateStore {

    private final Map<String, CircuitSnapshot> data = new HashMap<>();

    @Override
    public <T> T update(String circuitKey, StateTransition<T> transition) {
      Update<T> update = transition.apply(data.get(circuitKey));
      data.put(circuitKey, update.getNewSnapshot());
      return update.getResult();
    }
  }

  /** A {@link Chain} that immediately calls either the success or the error consumer. */
  private static final class FakeChain implements Chain {

    boolean invoked;
    private final boolean fail;
    private final Object output;
    private final Throwable error;

    private FakeChain(boolean fail, Object output, Throwable error) {
      this.fail = fail;
      this.output = output;
      this.error = error;
    }

    static FakeChain success(Object output) {
      return new FakeChain(false, output, null);
    }

    static FakeChain failure(Throwable error) {
      return new FakeChain(true, null, error);
    }

    @Override
    public void process(Consumer<Result> onSuccess, BiConsumer<Throwable, Result> onError) {
      invoked = true;
      if (fail) {
        onError.accept(error, Result.builder().build());
      } else {
        onSuccess.accept(Result.builder().output(output).build());
      }
    }

    @Override
    public void process(Object payload, Object attributes, Consumer<Result> onSuccess, BiConsumer<Throwable, Result> onError) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void process(Result input, Consumer<Result> onSuccess, BiConsumer<Throwable, Result> onError) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class CapturingCallback implements CompletionCallback<Object, Object> {

    boolean errored;
    Throwable error;
    Result<Object, Object> successResult;

    @Override
    public void success(Result<Object, Object> result) {
      this.successResult = result;
    }

    @Override
    public void error(Throwable throwable) {
      this.errored = true;
      this.error = throwable;
    }
  }

  private static final class FakeErrorType implements ErrorType {

    private final String namespace;
    private final String identifier;

    FakeErrorType(String namespace, String identifier) {
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
