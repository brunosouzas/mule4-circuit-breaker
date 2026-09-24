package com.brunosouzas.mule.extension.circuitbreaker.api.store;

/**
 * Port through which the plugin reads and writes circuit state, keyed by circuit. This is the extension
 * point named in ADR-001: the default implementation shipped with this plugin keeps state in the Mule
 * runtime's own Object Store for a single node; a later, separate module can add an adapter backed by a
 * shared/distributed store for multi-replica deployments without changing any decision logic, because
 * every caller only ever depends on this interface.
 *
 * <p>There is a single method, deliberately: {@link #update} performs an atomic read-decide-write cycle,
 * so all concurrency handling (locking, compare-and-swap, or whatever a given backend requires) is the
 * adapter's responsibility, and the pure decision logic that calls it never has to reason about races.
 */
public interface CircuitStateStore {

  /**
   * Atomically reads the current snapshot for {@code circuitKey} (or {@code null} if the circuit has no
   * recorded state yet), applies {@code transition} to compute the next snapshot and a result, persists
   * the next snapshot, and returns the result.
   */
  <T> T update(String circuitKey, StateTransition<T> transition);

  /** Computes the next {@link CircuitSnapshot} for a circuit, and a caller-defined result, from the current one. */
  @FunctionalInterface
  interface StateTransition<T> {
    Update<T> apply(CircuitSnapshot current);
  }

  /** The outcome of a {@link StateTransition}: the snapshot to persist, and the value to return to the caller. */
  final class Update<T> {

    private final CircuitSnapshot newSnapshot;
    private final T result;

    public Update(CircuitSnapshot newSnapshot, T result) {
      this.newSnapshot = newSnapshot;
      this.result = result;
    }

    public CircuitSnapshot getNewSnapshot() {
      return newSnapshot;
    }

    public T getResult() {
      return result;
    }
  }
}
