package com.brunosouzas.mule.extension.circuitbreaker.internal.state;

import com.brunosouzas.mule.extension.circuitbreaker.api.store.CircuitSnapshot;

/**
 * Result of {@link CircuitEvaluator#onCallStart}: whether the call may proceed, and the snapshot to
 * persist (which may already reflect a lazy OPEN-to-HALF_OPEN transition or a newly issued HALF_OPEN
 * test permit, even when the call itself is blocked).
 */
public final class EvaluationOutcome {

  private final boolean allowed;
  private final CircuitSnapshot snapshot;

  public EvaluationOutcome(boolean allowed, CircuitSnapshot snapshot) {
    this.allowed = allowed;
    this.snapshot = snapshot;
  }

  public boolean isAllowed() {
    return allowed;
  }

  public CircuitSnapshot getSnapshot() {
    return snapshot;
  }
}
