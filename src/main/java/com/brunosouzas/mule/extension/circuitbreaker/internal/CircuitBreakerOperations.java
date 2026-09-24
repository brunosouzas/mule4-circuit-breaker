package com.brunosouzas.mule.extension.circuitbreaker.internal;

import com.brunosouzas.mule.extension.circuitbreaker.api.store.CircuitSnapshot;
import com.brunosouzas.mule.extension.circuitbreaker.api.store.CircuitStateStore;
import com.brunosouzas.mule.extension.circuitbreaker.internal.classify.ErrorClassifier;
import com.brunosouzas.mule.extension.circuitbreaker.internal.error.CircuitBreakerError;
import com.brunosouzas.mule.extension.circuitbreaker.internal.error.CircuitBreakerErrorTypeProvider;
import com.brunosouzas.mule.extension.circuitbreaker.internal.state.CircuitEvaluator;
import com.brunosouzas.mule.extension.circuitbreaker.internal.state.EvaluationOutcome;
import com.brunosouzas.mule.extension.circuitbreaker.internal.store.ObjectStoreCircuitStateStore;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.mule.runtime.api.lifecycle.Initialisable;
import org.mule.runtime.api.lifecycle.InitialisationException;
import org.mule.runtime.api.lock.LockFactory;
import org.mule.runtime.api.store.ObjectStoreManager;
import org.mule.runtime.extension.api.annotation.error.Throws;
import org.mule.runtime.extension.api.annotation.param.Optional;
import org.mule.runtime.extension.api.annotation.param.MediaType;
import org.mule.runtime.extension.api.annotation.param.display.Summary;
import org.mule.runtime.extension.api.exception.ModuleException;
import org.mule.runtime.extension.api.runtime.process.CompletionCallback;
import org.mule.runtime.extension.api.runtime.route.Chain;

/**
 * The single execution scope decided in ADR-001: wraps a nested processor chain, checks and updates
 * circuit state around it, and reports the outcome automatically through the chain's own success/error
 * path — no second, explicit "report result" operation for the flow author to remember.
 *
 * <p>A scope component (one with a nested {@link Chain}) cannot take a {@code @Config} parameter,
 * {@code @ConfigOverride} parameters, or complex/list-typed parameters at all (Mule SDK model
 * validation rejects all three for this component shape) — so every tunable is a plain, attribute-only
 * parameter directly on {@code execute}, {@code circuitBreakerKey} is mandatory (there is no config to
 * default it from), and {@code failureErrorTypes} is a comma-separated string rather than a list. State
 * storage is fixed to the Object Store adapter for the same reason: the ADR's pluggable
 * {@code CircuitStateStore} port still exists and is still the seam BRU-54 will extend, just not as a
 * DSL-configurable parameter — a distributed adapter will need real integration code of its own regardless
 * of whether XML could select it.
 */
public class CircuitBreakerOperations implements Initialisable {

  private final CircuitEvaluator evaluator = new CircuitEvaluator();
  private final ErrorClassifier errorClassifier = new ErrorClassifier();

  @Inject
  private ObjectStoreManager objectStoreManager;

  @Inject
  private LockFactory lockFactory;

  // Package-private rather than private: lets CircuitBreakerOperationsTest inject a fake store and
  // call execute() directly, without needing a real Mule runtime to drive @Inject/initialise().
  CircuitStateStore stateStore;

  @Override
  public void initialise() throws InitialisationException {
    ObjectStoreCircuitStateStore store = new ObjectStoreCircuitStateStore();
    store.bind(objectStoreManager, lockFactory);
    this.stateStore = store;
  }

  @MediaType(value = MediaType.ANY, strict = false)
  @Throws(CircuitBreakerErrorTypeProvider.class)
  public void execute(
      @Summary("Identifies the circuit this call belongs to. Calls sharing the same key share the same "
          + "circuit state.") String circuitBreakerKey,
      @Optional(defaultValue = "20")
      @Summary("Number of recent calls kept in the sliding window used to compute the failure rate.") int windowSize,
      @Optional(defaultValue = "10")
      @Summary("Minimum number of calls in the window before the failure rate is evaluated.") int minimumCalls,
      @Optional(defaultValue = "50")
      @Summary("Failure percentage in the window (0-100, exclusive) that opens the circuit.") int failureRateThreshold,
      @Optional(defaultValue = "30000")
      @Summary("Time in milliseconds the circuit stays OPEN before allowing a HALF_OPEN test call.") long resetTimeout,
      @Optional(defaultValue = "1")
      @Summary("Number of test calls allowed while the circuit is HALF_OPEN.") int permittedCallsInHalfOpen,
      @Optional(defaultValue = "1")
      @Summary("Number of successful HALF_OPEN test calls required to close the circuit again.") int successThreshold,
      @Optional(defaultValue = "")
      @Summary("Comma-separated Mule error types (NAMESPACE:IDENTIFIER) that count as a circuit failure. Any "
          + "other error type never opens the circuit.") String failureErrorTypes,
      Chain operations,
      CompletionCallback<Object, Object> callback) {

    CircuitBreakerParameters params = new CircuitBreakerParameters(windowSize, minimumCalls, failureRateThreshold,
        resetTimeout, permittedCallsInHalfOpen, successThreshold, parseErrorTypes(failureErrorTypes));

    boolean allowed = stateStore.update(circuitBreakerKey, current -> {
      EvaluationOutcome outcome = evaluator.onCallStart(current, params, System.currentTimeMillis());
      return new CircuitStateStore.Update<>(outcome.getSnapshot(), outcome.isAllowed());
    });

    if (!allowed) {
      callback.error(new ModuleException(
          "Circuit '" + circuitBreakerKey + "' is OPEN; call was not attempted", CircuitBreakerError.OPEN));
      return;
    }

    operations.process(
        result -> {
          recordOutcome(circuitBreakerKey, false, params);
          callback.success(result);
        },
        (throwable, result) -> {
          boolean isFailure = errorClassifier.isCircuitFailure(throwable, params.getFailureErrorTypes());
          recordOutcome(circuitBreakerKey, isFailure, params);
          callback.error(throwable);
        });
  }

  private void recordOutcome(String circuitKey, boolean isFailure, CircuitBreakerParameters params) {
    stateStore.<Void>update(circuitKey, current -> {
      CircuitSnapshot next = evaluator.onCallOutcome(current, isFailure, params, System.currentTimeMillis());
      return new CircuitStateStore.Update<>(next, null);
    });
  }

  private static List<String> parseErrorTypes(String failureErrorTypes) {
    if (failureErrorTypes == null || failureErrorTypes.trim().isEmpty()) {
      return Collections.emptyList();
    }
    return Arrays.stream(failureErrorTypes.split(","))
        .map(String::trim)
        .filter(type -> !type.isEmpty())
        .collect(Collectors.toList());
  }
}
