package com.brunosouzas.mule.extension.circuitbreaker.internal.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.brunosouzas.mule.extension.circuitbreaker.api.store.CircuitSnapshot;
import com.brunosouzas.mule.extension.circuitbreaker.api.store.CircuitState;
import com.brunosouzas.mule.extension.circuitbreaker.internal.CircuitBreakerParameters;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/**
 * One test per transition (and per "stay in state") drawn directly from ADR-001's state diagram:
 * CLOSED-OPEN-HALF_OPEN-CLOSED/OPEN, plus the minimumCalls and permittedCallsInHalfOpen guards.
 */
class CircuitEvaluatorTest {

  private static final long START = 1_000_000L;

  private final CircuitEvaluator evaluator = new CircuitEvaluator();

  // windowSize=4, minimumCalls=4, failureRateThreshold=50 (strictly greater than), resetTimeout=1000ms,
  // permittedCallsInHalfOpen=2, successThreshold=2.
  private CircuitBreakerParameters params() {
    return new CircuitBreakerParameters(4, 4, 50, 1000L, 2, 2, Collections.emptyList());
  }

  @Test
  void firstCallOnAFreshCircuitIsAllowedAndInitialisesClosed() {
    EvaluationOutcome outcome = evaluator.onCallStart(null, params(), START);
    assertTrue(outcome.isAllowed());
    assertEquals(CircuitState.CLOSED, outcome.getSnapshot().getState());
  }

  @Test
  void closedStaysClosedBelowMinimumCallsEvenWithHighLocalFailureRate() {
    CircuitSnapshot snapshot = CircuitSnapshot.initial(4, START);
    // 2 failures recorded, but minimumCalls is 4: must not open yet, regardless of local 100% rate.
    snapshot = evaluator.onCallOutcome(snapshot, true, params(), START);
    snapshot = evaluator.onCallOutcome(snapshot, true, params(), START);

    assertEquals(CircuitState.CLOSED, snapshot.getState());
    assertFalse(snapshot.getWindow().hasMinimumCalls(4));
  }

  @Test
  void closedOpensWhenFailureRateExceedsThresholdOnceMinimumCallsReached() {
    CircuitSnapshot snapshot = CircuitSnapshot.initial(4, START);
    // 3 failures, 1 success out of 4 calls = 75% > 50% threshold.
    snapshot = evaluator.onCallOutcome(snapshot, true, params(), START);
    snapshot = evaluator.onCallOutcome(snapshot, true, params(), START);
    snapshot = evaluator.onCallOutcome(snapshot, true, params(), START);
    snapshot = evaluator.onCallOutcome(snapshot, false, params(), START);

    assertEquals(CircuitState.OPEN, snapshot.getState());
    assertEquals(START, snapshot.getStateChangedAtEpochMs());
  }

  @Test
  void closedStaysClosedWhenFailureRateAtOrBelowThreshold() {
    CircuitSnapshot snapshot = CircuitSnapshot.initial(4, START);
    // 2 failures, 2 successes = 50%, not strictly greater than the 50% threshold.
    snapshot = evaluator.onCallOutcome(snapshot, true, params(), START);
    snapshot = evaluator.onCallOutcome(snapshot, true, params(), START);
    snapshot = evaluator.onCallOutcome(snapshot, false, params(), START);
    snapshot = evaluator.onCallOutcome(snapshot, false, params(), START);

    assertEquals(CircuitState.CLOSED, snapshot.getState());
  }

  @Test
  void openStaysOpenAndBlocksBeforeResetTimeoutElapses() {
    CircuitSnapshot open = new CircuitSnapshot(CircuitState.OPEN, com.brunosouzas.mule.extension.circuitbreaker.api.store.SlidingWindow.empty(4), START, 0, 0);

    EvaluationOutcome outcome = evaluator.onCallStart(open, params(), START + 999L);

    assertFalse(outcome.isAllowed());
    assertEquals(CircuitState.OPEN, outcome.getSnapshot().getState());
  }

  @Test
  void openMovesToHalfOpenAndAllowsTheCallOnceResetTimeoutElapses() {
    CircuitSnapshot open = new CircuitSnapshot(CircuitState.OPEN, com.brunosouzas.mule.extension.circuitbreaker.api.store.SlidingWindow.empty(4), START, 0, 0);

    long now = START + 1000L;
    EvaluationOutcome outcome = evaluator.onCallStart(open, params(), now);

    assertTrue(outcome.isAllowed());
    assertEquals(CircuitState.HALF_OPEN, outcome.getSnapshot().getState());
    assertEquals(now, outcome.getSnapshot().getStateChangedAtEpochMs());
    assertEquals(1, outcome.getSnapshot().getHalfOpenPermitsIssued());
  }

  @Test
  void halfOpenStaysHalfOpenWhileSuccessesBelowThreshold() {
    CircuitSnapshot halfOpen = new CircuitSnapshot(CircuitState.HALF_OPEN, com.brunosouzas.mule.extension.circuitbreaker.api.store.SlidingWindow.empty(4), START, 1, 0);

    CircuitSnapshot next = evaluator.onCallOutcome(halfOpen, false, params(), START + 10);

    assertEquals(CircuitState.HALF_OPEN, next.getState());
    assertEquals(1, next.getHalfOpenSuccesses());
  }

  @Test
  void halfOpenClosesAndResetsWindowOnceSuccessThresholdReached() {
    CircuitSnapshot halfOpen = new CircuitSnapshot(CircuitState.HALF_OPEN, com.brunosouzas.mule.extension.circuitbreaker.api.store.SlidingWindow.empty(4), START, 1, 1);

    CircuitSnapshot next = evaluator.onCallOutcome(halfOpen, false, params(), START + 20);

    assertEquals(CircuitState.CLOSED, next.getState());
    assertEquals(0, next.getHalfOpenPermitsIssued());
    assertEquals(0, next.getHalfOpenSuccesses());
    assertEquals(0, next.getWindow().getFilledCount());
  }

  @Test
  void halfOpenReopensImmediatelyOnAnyTestFailureRegardlessOfPriorSuccesses() {
    CircuitSnapshot halfOpen = new CircuitSnapshot(CircuitState.HALF_OPEN, com.brunosouzas.mule.extension.circuitbreaker.api.store.SlidingWindow.empty(4), START, 2, 1);

    CircuitSnapshot next = evaluator.onCallOutcome(halfOpen, true, params(), START + 30);

    assertEquals(CircuitState.OPEN, next.getState());
    assertEquals(START + 30, next.getStateChangedAtEpochMs());
    assertEquals(0, next.getHalfOpenPermitsIssued());
    assertEquals(0, next.getHalfOpenSuccesses());
  }

  @Test
  void halfOpenBlocksOnceAllTestPermitsAreIssued() {
    CircuitSnapshot halfOpen = new CircuitSnapshot(CircuitState.HALF_OPEN, com.brunosouzas.mule.extension.circuitbreaker.api.store.SlidingWindow.empty(4), START, 2, 0);

    EvaluationOutcome outcome = evaluator.onCallStart(halfOpen, params(), START + 40);

    assertFalse(outcome.isAllowed());
    assertEquals(CircuitState.HALF_OPEN, outcome.getSnapshot().getState());
    assertEquals(2, outcome.getSnapshot().getHalfOpenPermitsIssued());
  }

  @Test
  void halfOpenIssuesAPermitWhenUnderTheLimit() {
    CircuitSnapshot halfOpen = new CircuitSnapshot(CircuitState.HALF_OPEN, com.brunosouzas.mule.extension.circuitbreaker.api.store.SlidingWindow.empty(4), START, 1, 0);

    EvaluationOutcome outcome = evaluator.onCallStart(halfOpen, params(), START + 40);

    assertTrue(outcome.isAllowed());
    assertEquals(2, outcome.getSnapshot().getHalfOpenPermitsIssued());
  }
}
