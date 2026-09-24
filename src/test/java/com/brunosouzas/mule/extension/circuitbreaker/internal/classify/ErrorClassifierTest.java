package com.brunosouzas.mule.extension.circuitbreaker.internal.classify;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mule.runtime.api.exception.TypedException;
import org.mule.runtime.api.message.ErrorType;

class ErrorClassifierTest {

  private final ErrorClassifier classifier = new ErrorClassifier();

  @Test
  void configuredTypeCountsAsFailure() {
    ErrorType timeout = new FakeErrorType("HTTP", "TIMEOUT", null);
    TypedException thrown = new TypedException(new RuntimeException("boom"), timeout);

    assertTrue(classifier.isCircuitFailure(thrown, List.of("HTTP:TIMEOUT")));
  }

  @Test
  void unconfiguredBusinessErrorNeverCountsAsFailure() {
    ErrorType validation = new FakeErrorType("MULE", "VALIDATION", null);
    TypedException thrown = new TypedException(new RuntimeException("bad payload"), validation);

    assertFalse(classifier.isCircuitFailure(thrown, List.of("HTTP:TIMEOUT", "HTTP:CONNECTIVITY")));
  }

  @Test
  void noFailureErrorTypesConfiguredNeverCounts() {
    ErrorType timeout = new FakeErrorType("HTTP", "TIMEOUT", null);
    TypedException thrown = new TypedException(new RuntimeException("boom"), timeout);

    assertFalse(classifier.isCircuitFailure(thrown, Collections.emptyList()));
    assertFalse(classifier.isCircuitFailure(thrown, null));
  }

  @Test
  void matchesAConfiguredParentTypeEvenWhenTheRaisedTypeIsAMoreSpecificSubtype() {
    ErrorType parent = new FakeErrorType("MULE", "CONNECTIVITY", null);
    ErrorType child = new FakeErrorType("HTTP", "CONNECTIVITY", parent);
    TypedException thrown = new TypedException(new RuntimeException("connection refused"), child);

    assertTrue(classifier.isCircuitFailure(thrown, List.of("MULE:CONNECTIVITY")));
  }

  @Test
  void unwrapsATypedExceptionFoundDeeperInTheCauseChain() {
    ErrorType timeout = new FakeErrorType("HTTP", "TIMEOUT", null);
    TypedException typed = new TypedException(new RuntimeException("boom"), timeout);
    RuntimeException wrapper = new RuntimeException("wrapped", typed);

    assertTrue(classifier.isCircuitFailure(wrapper, List.of("HTTP:TIMEOUT")));
  }

  @Test
  void throwableWithNoTypedExceptionInItsCauseChainNeverCounts() {
    RuntimeException plain = new RuntimeException("no error type here");

    assertFalse(classifier.isCircuitFailure(plain, List.of("HTTP:TIMEOUT")));
  }

  /** Minimal {@link ErrorType} test double: real Mule error types are runtime-registry-bound objects. */
  private static final class FakeErrorType implements ErrorType {

    private final String namespace;
    private final String identifier;
    private final ErrorType parent;

    FakeErrorType(String namespace, String identifier, ErrorType parent) {
      this.namespace = namespace;
      this.identifier = identifier;
      this.parent = parent;
    }

    @Override
    public String getIdentifier() {
      return identifier;
    }

    @Override
    public String getNamespace() {
      return namespace;
    }

    @Override
    public ErrorType getParentErrorType() {
      return parent;
    }
  }
}
