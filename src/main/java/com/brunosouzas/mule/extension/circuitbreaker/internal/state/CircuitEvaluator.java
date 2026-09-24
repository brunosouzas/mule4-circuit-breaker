package com.brunosouzas.mule.extension.circuitbreaker.internal.state;

import com.brunosouzas.mule.extension.circuitbreaker.api.store.CircuitSnapshot;
import com.brunosouzas.mule.extension.circuitbreaker.api.store.CircuitState;
import com.brunosouzas.mule.extension.circuitbreaker.api.store.SlidingWindow;
import com.brunosouzas.mule.extension.circuitbreaker.internal.CircuitBreakerParameters;

/**
 * Pure decision logic for the circuit's state machine, exactly as decided in ADR-001. Never performs I/O:
 * every method is a function of its inputs, so it can be unit-tested without a Mule runtime and without any
 * knowledge of how/where state is stored. The {@code CircuitStateStore} adapter is the only thing that
 * calls this class, inside its atomic read-decide-write cycle.
 */
public final class CircuitEvaluator {

  /**
   * Decides whether a new call may proceed. CLOSED always allows. OPEN blocks until {@code resetTimeout}
   * has elapsed, at which point the next call lazily moves the circuit to HALF_OPEN and is itself treated
   * as the first test call. HALF_OPEN allows calls only while fewer than {@code permittedCallsInHalfOpen}
   * test permits have been issued, protecting a still-recovering backend from a burst of test traffic.
   */
  public EvaluationOutcome onCallStart(CircuitSnapshot current, CircuitBreakerParameters params, long nowEpochMs) {
    CircuitSnapshot snapshot = current != null ? current : CircuitSnapshot.initial(params.getWindowSize(), nowEpochMs);

    switch (snapshot.getState()) {
      case CLOSED:
        return new EvaluationOutcome(true, snapshot);

      case OPEN: {
        long elapsed = nowEpochMs - snapshot.getStateChangedAtEpochMs();
        if (elapsed < params.getResetTimeoutMillis()) {
          return new EvaluationOutcome(false, snapshot);
        }
        CircuitSnapshot halfOpen = snapshot.withState(CircuitState.HALF_OPEN, nowEpochMs)
            .withHalfOpenCounters(1, 0);
        return new EvaluationOutcome(true, halfOpen);
      }

      case HALF_OPEN: {
        if (snapshot.getHalfOpenPermitsIssued() >= params.getPermittedCallsInHalfOpen()) {
          return new EvaluationOutcome(false, snapshot);
        }
        CircuitSnapshot withPermit = snapshot.withHalfOpenCounters(
            snapshot.getHalfOpenPermitsIssued() + 1, snapshot.getHalfOpenSuccesses());
        return new EvaluationOutcome(true, withPermit);
      }

      default:
        throw new IllegalStateException("Unhandled circuit state: " + snapshot.getState());
    }
  }

  /**
   * Records the outcome of a call that was allowed to proceed, returning the snapshot to persist. CLOSED
   * appends to the sliding window and opens the circuit once the failure rate exceeds the threshold, given
   * enough calls in the window. HALF_OPEN closes the circuit (resetting the window) once enough test calls
   * succeed, and reopens immediately on any test failure.
   */
  public CircuitSnapshot onCallOutcome(CircuitSnapshot current, boolean isFailure, CircuitBreakerParameters params,
      long nowEpochMs) {
    switch (current.getState()) {
      case CLOSED: {
        SlidingWindow window = current.getWindow().record(isFailure);
        if (window.hasMinimumCalls(params.getMinimumCalls())
            && window.failureRatePercent() > params.getFailureRateThreshold()) {
          return current.withWindow(window).withState(CircuitState.OPEN, nowEpochMs);
        }
        return current.withWindow(window);
      }

      case HALF_OPEN: {
        if (isFailure) {
          return current.withState(CircuitState.OPEN, nowEpochMs).withHalfOpenCounters(0, 0);
        }
        int successes = current.getHalfOpenSuccesses() + 1;
        if (successes >= params.getSuccessThreshold()) {
          return current.withState(CircuitState.CLOSED, nowEpochMs)
              .withWindow(SlidingWindow.empty(params.getWindowSize()))
              .withHalfOpenCounters(0, 0);
        }
        return current.withHalfOpenCounters(current.getHalfOpenPermitsIssued(), successes);
      }

      case OPEN:
      default:
        // A call that reaches this point must have been allowed by onCallStart, which never allows a call
        // while OPEN; reaching here would mean the caller invoked onCallOutcome without a matching
        // onCallStart. Leave the snapshot untouched rather than corrupt it.
        return current;
    }
  }
}
