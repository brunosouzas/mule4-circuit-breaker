package com.brunosouzas.mule.extension.circuitbreaker.api.store;

/**
 * The three states a circuit can be in, per the accepted design (ADR-001): calls flow normally in
 * {@link #CLOSED}, are rejected in {@link #OPEN}, and a limited number of test calls are allowed in
 * {@link #HALF_OPEN} to probe recovery.
 */
public enum CircuitState {
  CLOSED,
  OPEN,
  HALF_OPEN
}
