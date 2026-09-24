package com.brunosouzas.mule.extension.circuitbreaker.internal;

import com.brunosouzas.mule.extension.circuitbreaker.internal.error.CircuitBreakerError;
import org.mule.runtime.api.meta.Category;
import org.mule.runtime.extension.api.annotation.Configurations;
import org.mule.runtime.extension.api.annotation.Extension;
import org.mule.runtime.extension.api.annotation.dsl.xml.Xml;
import org.mule.runtime.extension.api.annotation.error.ErrorTypes;
import org.mule.sdk.api.annotation.JavaVersionSupport;
import org.mule.sdk.api.meta.JavaVersion;

/**
 * Circuit breaker plugin for Mule 4 (ADR-001): a sliding-window failure-rate trigger, a single wrapping
 * operation that reports its own outcome.
 */
@Extension(name = "Circuit Breaker", vendor = "brunosouzas", category = Category.COMMUNITY)
@Xml(prefix = "circuit-breaker", namespace = "http://www.mulesoft.org/schema/mule/circuit-breaker")
@Configurations(CircuitBreakerConfiguration.class)
@ErrorTypes(CircuitBreakerError.class)
// Pinned explicitly to JAVA_17 (this project's build target): without this, the SDK defaults to
// declaring support for every JavaVersion constant on the compile-time mule-sdk-api, including ones
// (JAVA_25) that an older mule-sdk-api bundled inside a given Mule runtime doesn't recognize yet,
// crashing extension-model parsing with EnumConstantNotPresentException at deploy time.
@JavaVersionSupport(JavaVersion.JAVA_17)
public class CircuitBreakerExtension {
}
