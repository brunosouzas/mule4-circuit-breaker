package com.brunosouzas.mule.extension.circuitbreaker.internal;

import org.mule.runtime.extension.api.annotation.Operations;

/**
 * Marker configuration: this extension's single behaviour lives entirely in the {@code execute} scope
 * operation. A Mule extension still needs at least one {@code @Configurations} entry to be declarable in
 * XML as {@code <circuit-breaker:config>}, even though a scope component (one with a nested chain) cannot
 * itself take a {@code @Config} parameter or read values through {@code @ConfigOverride} — every tunable
 * is therefore a plain parameter directly on {@link CircuitBreakerOperations#execute}.
 */
@Operations(CircuitBreakerOperations.class)
public class CircuitBreakerConfiguration {
}
