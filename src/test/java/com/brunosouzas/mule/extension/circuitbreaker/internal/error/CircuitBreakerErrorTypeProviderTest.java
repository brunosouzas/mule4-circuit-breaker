package com.brunosouzas.mule.extension.circuitbreaker.internal.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mule.runtime.extension.api.error.ErrorTypeDefinition;

class CircuitBreakerErrorTypeProviderTest {

  @Test
  void declaresOnlyTheOpenErrorType() {
    Set<ErrorTypeDefinition> declared = new CircuitBreakerErrorTypeProvider().getErrorTypes();

    assertEquals(1, declared.size());
    assertTrue(declared.contains(CircuitBreakerError.OPEN));
  }
}
