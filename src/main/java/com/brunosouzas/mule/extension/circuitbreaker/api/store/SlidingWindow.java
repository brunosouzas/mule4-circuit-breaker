package com.brunosouzas.mule.extension.circuitbreaker.api.store;

import java.io.Serializable;
import java.util.BitSet;
import java.util.Objects;

/**
 * A fixed-size circular buffer of the last {@code capacity} call outcomes (failure/success), used to
 * evaluate the failure rate that drives the CLOSED-to-OPEN transition (ADR-001).
 *
 * <p>Outcomes are packed one bit per call in a {@link BitSet} (1 = failure, 0 = success) rather than a
 * {@code boolean[]}, keeping the serialized payload small. A running {@code failureCount} is kept in sync
 * on every write, so the failure rate never requires rescanning the window: recording an outcome and
 * reading the rate are both O(1), which keeps the per-call overhead of the plugin negligible, and the
 * object is immediately usable after deserialization from a state store with no recomputation step.
 *
 * <p>Immutable: {@link #record(boolean)} returns a new instance rather than mutating this one, matching
 * the read-decide-write style of {@link CircuitStateStore#update}.
 */
public final class SlidingWindow implements Serializable {

  private static final long serialVersionUID = 1L;

  private final BitSet outcomes;
  private final int capacity;
  private int writeIndex;
  private int filledCount;
  private int failureCount;

  private SlidingWindow(BitSet outcomes, int capacity, int writeIndex, int filledCount, int failureCount) {
    this.outcomes = outcomes;
    this.capacity = capacity;
    this.writeIndex = writeIndex;
    this.filledCount = filledCount;
    this.failureCount = failureCount;
  }

  public static SlidingWindow empty(int capacity) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("windowSize must be greater than zero, got " + capacity);
    }
    return new SlidingWindow(new BitSet(capacity), capacity, 0, 0, 0);
  }

  /**
   * Returns a new window with {@code isFailure} recorded as the most recent outcome, evicting the oldest
   * outcome once the window has wrapped around.
   */
  public SlidingWindow record(boolean isFailure) {
    BitSet nextOutcomes = (BitSet) outcomes.clone();
    int nextFailureCount = failureCount;

    boolean wasWrapping = filledCount == capacity;
    if (wasWrapping && outcomes.get(writeIndex)) {
      nextFailureCount--;
    }

    nextOutcomes.set(writeIndex, isFailure);
    if (isFailure) {
      nextFailureCount++;
    }

    int nextWriteIndex = (writeIndex + 1) % capacity;
    int nextFilledCount = Math.min(filledCount + 1, capacity);

    return new SlidingWindow(nextOutcomes, capacity, nextWriteIndex, nextFilledCount, nextFailureCount);
  }

  public boolean hasMinimumCalls(int minimumCalls) {
    return filledCount >= minimumCalls;
  }

  /** Failure rate in the window, as a whole percentage (0-100). Zero when the window is empty. */
  public int failureRatePercent() {
    return filledCount == 0 ? 0 : (failureCount * 100) / filledCount;
  }

  public int getCapacity() {
    return capacity;
  }

  public int getFilledCount() {
    return filledCount;
  }

  public int getFailureCount() {
    return failureCount;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof SlidingWindow)) {
      return false;
    }
    SlidingWindow that = (SlidingWindow) o;
    return capacity == that.capacity
        && writeIndex == that.writeIndex
        && filledCount == that.filledCount
        && failureCount == that.failureCount
        && outcomes.equals(that.outcomes);
  }

  @Override
  public int hashCode() {
    return Objects.hash(outcomes, capacity, writeIndex, filledCount, failureCount);
  }

  @Override
  public String toString() {
    return "SlidingWindow{filled=" + filledCount + "/" + capacity + ", failures=" + failureCount + "}";
  }
}
