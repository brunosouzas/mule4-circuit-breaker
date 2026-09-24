package com.brunosouzas.mule.extension.circuitbreaker.api.store;

import java.io.Serializable;
import java.util.Objects;

/**
 * Immutable snapshot of a single circuit's state: its {@link CircuitState}, its {@link SlidingWindow} of
 * recent outcomes, when it last changed state, and how many HALF_OPEN test calls it has issued/won.
 *
 * <p>This is the single value a {@link CircuitStateStore} implementation persists per circuit key
 * (ADR-001: "uma chave por config, mais uma chave de operação para escopo fino"). Keeping every piece of a
 * circuit's state in one serialized value, written atomically by {@link CircuitStateStore#update}, avoids
 * a torn read where part of the state (say, the state enum) is newer than another part (say, the window).
 */
public final class CircuitSnapshot implements Serializable {

  private static final long serialVersionUID = 1L;

  private final CircuitState state;
  private final SlidingWindow window;
  private final long stateChangedAtEpochMs;
  private final int halfOpenPermitsIssued;
  private final int halfOpenSuccesses;

  public CircuitSnapshot(CircuitState state, SlidingWindow window, long stateChangedAtEpochMs,
      int halfOpenPermitsIssued, int halfOpenSuccesses) {
    this.state = Objects.requireNonNull(state, "state");
    this.window = Objects.requireNonNull(window, "window");
    this.stateChangedAtEpochMs = stateChangedAtEpochMs;
    this.halfOpenPermitsIssued = halfOpenPermitsIssued;
    this.halfOpenSuccesses = halfOpenSuccesses;
  }

  /** The initial snapshot for a circuit that has never recorded a call: CLOSED, empty window. */
  public static CircuitSnapshot initial(int windowSize, long nowEpochMs) {
    return new CircuitSnapshot(CircuitState.CLOSED, SlidingWindow.empty(windowSize), nowEpochMs, 0, 0);
  }

  public CircuitSnapshot withState(CircuitState newState, long changedAtEpochMs) {
    return new CircuitSnapshot(newState, window, changedAtEpochMs, halfOpenPermitsIssued, halfOpenSuccesses);
  }

  public CircuitSnapshot withWindow(SlidingWindow newWindow) {
    return new CircuitSnapshot(state, newWindow, stateChangedAtEpochMs, halfOpenPermitsIssued, halfOpenSuccesses);
  }

  public CircuitSnapshot withHalfOpenCounters(int newHalfOpenPermitsIssued, int newHalfOpenSuccesses) {
    return new CircuitSnapshot(state, window, stateChangedAtEpochMs, newHalfOpenPermitsIssued, newHalfOpenSuccesses);
  }

  public CircuitState getState() {
    return state;
  }

  public SlidingWindow getWindow() {
    return window;
  }

  public long getStateChangedAtEpochMs() {
    return stateChangedAtEpochMs;
  }

  public int getHalfOpenPermitsIssued() {
    return halfOpenPermitsIssued;
  }

  public int getHalfOpenSuccesses() {
    return halfOpenSuccesses;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof CircuitSnapshot)) {
      return false;
    }
    CircuitSnapshot that = (CircuitSnapshot) o;
    return stateChangedAtEpochMs == that.stateChangedAtEpochMs
        && halfOpenPermitsIssued == that.halfOpenPermitsIssued
        && halfOpenSuccesses == that.halfOpenSuccesses
        && state == that.state
        && window.equals(that.window);
  }

  @Override
  public int hashCode() {
    return Objects.hash(state, window, stateChangedAtEpochMs, halfOpenPermitsIssued, halfOpenSuccesses);
  }

  @Override
  public String toString() {
    return "CircuitSnapshot{state=" + state + ", window=" + window + ", stateChangedAtEpochMs="
        + stateChangedAtEpochMs + ", halfOpenPermitsIssued=" + halfOpenPermitsIssued + ", halfOpenSuccesses="
        + halfOpenSuccesses + "}";
  }
}
