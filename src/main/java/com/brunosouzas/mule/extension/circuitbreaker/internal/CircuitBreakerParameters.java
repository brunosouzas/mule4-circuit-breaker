package com.brunosouzas.mule.extension.circuitbreaker.internal;

import java.util.List;

/**
 * The resolved set of tunables for one {@code execute} call: config-level defaults, with any
 * operation-level {@code @ConfigOverride} applied on top. Passed to the pure decision logic in
 * {@code internal.state} so it never has to know about Mule SDK parameter resolution.
 */
public final class CircuitBreakerParameters {

  private final int windowSize;
  private final int minimumCalls;
  private final int failureRateThreshold;
  private final long resetTimeoutMillis;
  private final int permittedCallsInHalfOpen;
  private final int successThreshold;
  private final List<String> failureErrorTypes;

  public CircuitBreakerParameters(int windowSize, int minimumCalls, int failureRateThreshold,
      long resetTimeoutMillis, int permittedCallsInHalfOpen, int successThreshold, List<String> failureErrorTypes) {
    this.windowSize = windowSize;
    this.minimumCalls = minimumCalls;
    this.failureRateThreshold = failureRateThreshold;
    this.resetTimeoutMillis = resetTimeoutMillis;
    this.permittedCallsInHalfOpen = permittedCallsInHalfOpen;
    this.successThreshold = successThreshold;
    this.failureErrorTypes = failureErrorTypes;
  }

  public int getWindowSize() {
    return windowSize;
  }

  public int getMinimumCalls() {
    return minimumCalls;
  }

  public int getFailureRateThreshold() {
    return failureRateThreshold;
  }

  public long getResetTimeoutMillis() {
    return resetTimeoutMillis;
  }

  public int getPermittedCallsInHalfOpen() {
    return permittedCallsInHalfOpen;
  }

  public int getSuccessThreshold() {
    return successThreshold;
  }

  public List<String> getFailureErrorTypes() {
    return failureErrorTypes;
  }
}
