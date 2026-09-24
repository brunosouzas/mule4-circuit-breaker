package com.brunosouzas.mule.extension.circuitbreaker.internal.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.brunosouzas.mule.extension.circuitbreaker.api.store.SlidingWindow;
import org.junit.jupiter.api.Test;

class SlidingWindowTest {

  @Test
  void rejectsNonPositiveCapacity() {
    assertThrows(IllegalArgumentException.class, () -> SlidingWindow.empty(0));
    assertThrows(IllegalArgumentException.class, () -> SlidingWindow.empty(-1));
  }

  @Test
  void emptyWindowHasZeroRateAndNoMinimumCalls() {
    SlidingWindow window = SlidingWindow.empty(5);
    assertEquals(0, window.failureRatePercent());
    assertFalse(window.hasMinimumCalls(1));
  }

  @Test
  void belowMinimumCallsUntilThresholdReached() {
    SlidingWindow window = SlidingWindow.empty(10);
    for (int i = 0; i < 4; i++) {
      window = window.record(true);
    }
    assertFalse(window.hasMinimumCalls(5));

    window = window.record(true);
    assertTrue(window.hasMinimumCalls(5));
  }

  @Test
  void allSuccessWindowHasZeroFailureRate() {
    SlidingWindow window = SlidingWindow.empty(4);
    for (int i = 0; i < 4; i++) {
      window = window.record(false);
    }
    assertEquals(0, window.failureRatePercent());
    assertEquals(0, window.getFailureCount());
  }

  @Test
  void allFailureWindowHasHundredPercentRate() {
    SlidingWindow window = SlidingWindow.empty(4);
    for (int i = 0; i < 4; i++) {
      window = window.record(true);
    }
    assertEquals(100, window.failureRatePercent());
    assertEquals(4, window.getFailureCount());
  }

  @Test
  void failureRateAtExactBoundary() {
    // 2 failures out of 4 calls = 50%.
    SlidingWindow window = SlidingWindow.empty(4).record(true).record(true).record(false).record(false);
    assertEquals(50, window.failureRatePercent());
  }

  @Test
  void wrapAroundEvictsOldestOutcomeAndKeepsFailureCountInSync() {
    // Capacity 3: record F, F, S -> filled=3, failures=2. Next call evicts the oldest (the first F).
    SlidingWindow window = SlidingWindow.empty(3).record(true).record(true).record(false);
    assertEquals(3, window.getFilledCount());
    assertEquals(2, window.getFailureCount());

    // Evicts the first failure, records a success in its place: failures drop to 1, filled stays at 3.
    SlidingWindow wrapped = window.record(false);
    assertEquals(3, wrapped.getFilledCount());
    assertEquals(1, wrapped.getFailureCount());
    assertEquals(33, wrapped.failureRatePercent());
  }

  @Test
  void recordReturnsNewInstanceWithoutMutatingOriginal() {
    SlidingWindow original = SlidingWindow.empty(4).record(true);
    SlidingWindow updated = original.record(true);

    assertEquals(1, original.getFailureCount());
    assertEquals(2, updated.getFailureCount());
  }
}
