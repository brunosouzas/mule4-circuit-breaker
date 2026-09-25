package com.brunosouzas.mule.extension.circuitbreaker.internal.classify;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mule.runtime.api.exception.TypedException;
import org.mule.runtime.api.message.Error;
import org.mule.runtime.api.message.ErrorType;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.core.privileged.exception.MessagingException;

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

  /**
   * The shape actually handed to {@code Chain.process}'s error callback in a deployed flow, confirmed by
   * inspecting the real throwable rather than assumed: a {@link MessagingException} (or another
   * {@code EventProcessingException} subtype) wrapping a plain cause, never a bare {@link TypedException}.
   * Before this test existed, every test here used a raw {@link TypedException} directly, which is why a
   * classifier that only walked the cause chain for one never failed in this suite while still never
   * recognising a single real failure once deployed — the circuit could never open.
   */
  @Test
  void configuredTypeCountsAsFailureWhenCarriedByTheEventOfAMessagingException() {
    ErrorType timeout = new FakeErrorType("HTTP", "TIMEOUT", null);
    Error error = mock(Error.class);
    when(error.getErrorType()).thenReturn(timeout);
    CoreEvent event = mock(CoreEvent.class);
    when(event.getError()).thenReturn(Optional.of(error));
    MessagingException thrown = new MessagingException(event, new RuntimeException("connection refused"));

    assertTrue(classifier.isCircuitFailure(thrown, List.of("HTTP:TIMEOUT")));
  }

  @Test
  void unconfiguredBusinessErrorCarriedByTheEventOfAMessagingExceptionNeverCounts() {
    ErrorType validation = new FakeErrorType("MULE", "VALIDATION", null);
    Error error = mock(Error.class);
    when(error.getErrorType()).thenReturn(validation);
    CoreEvent event = mock(CoreEvent.class);
    when(event.getError()).thenReturn(Optional.of(error));
    MessagingException thrown = new MessagingException(event, new RuntimeException("bad payload"));

    assertFalse(classifier.isCircuitFailure(thrown, List.of("HTTP:TIMEOUT", "HTTP:CONNECTIVITY")));
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
