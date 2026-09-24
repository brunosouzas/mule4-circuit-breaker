package com.brunosouzas.mule.extension.circuitbreaker.internal.classify;

import java.util.List;
import org.mule.runtime.api.exception.TypedException;
import org.mule.runtime.api.message.ErrorType;

/**
 * Decides whether a failure raised by the wrapped processor chain counts as a circuit failure
 * (ADR-001: only configured "infrastructure" error types count; business/validation errors never do).
 *
 * <p>A Mule error type is looked up by unwrapping the given {@link Throwable} down to a
 * {@link TypedException} (walking the cause chain, since the chain's {@code Chain.process} error callback
 * may hand back a wrapper rather than the {@code TypedException} itself). A configured type matches either
 * exactly or as an ancestor of the raised type, so listing a parent type (e.g. {@code MULE:CONNECTIVITY})
 * also covers its subtypes.
 */
public class ErrorClassifier {

  public boolean isCircuitFailure(Throwable thrown, List<String> failureErrorTypes) {
    if (failureErrorTypes == null || failureErrorTypes.isEmpty()) {
      return false;
    }
    ErrorType errorType = unwrapErrorType(thrown);
    while (errorType != null) {
      if (failureErrorTypes.contains(formatIdentifier(errorType))) {
        return true;
      }
      errorType = errorType.getParentErrorType();
    }
    return false;
  }

  private static ErrorType unwrapErrorType(Throwable thrown) {
    Throwable current = thrown;
    while (current != null) {
      if (current instanceof TypedException) {
        return ((TypedException) current).getErrorType();
      }
      current = current.getCause();
    }
    return null;
  }

  private static String formatIdentifier(ErrorType errorType) {
    return errorType.getNamespace() + ":" + errorType.getIdentifier();
  }
}
