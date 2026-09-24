package com.brunosouzas.mule.extension.circuitbreaker.internal.error;

import java.util.Collections;
import java.util.Set;
import org.mule.runtime.extension.api.annotation.error.ErrorTypeProvider;
import org.mule.runtime.extension.api.error.ErrorTypeDefinition;

/** Declares that {@code execute} can raise {@link CircuitBreakerError#OPEN}, via {@code @Throws}. */
public class CircuitBreakerErrorTypeProvider implements ErrorTypeProvider {

  @Override
  public Set<ErrorTypeDefinition> getErrorTypes() {
    return Collections.singleton(CircuitBreakerError.OPEN);
  }
}
