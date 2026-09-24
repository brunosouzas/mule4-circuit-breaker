package com.brunosouzas.mule.extension.circuitbreaker.internal.error;

import org.mule.runtime.extension.api.error.ErrorTypeDefinition;

/**
 * Error types raised by this extension. {@code OPEN}, combined with the {@code circuit-breaker} XML
 * prefix (ADR-001), surfaces to flow authors as {@code CIRCUIT-BREAKER:OPEN}.
 */
public enum CircuitBreakerError implements ErrorTypeDefinition<CircuitBreakerError> {
  OPEN
}
