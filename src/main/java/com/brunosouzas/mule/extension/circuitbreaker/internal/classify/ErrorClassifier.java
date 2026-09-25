package com.brunosouzas.mule.extension.circuitbreaker.internal.classify;

import java.util.List;
import java.util.Optional;
import org.mule.runtime.api.exception.TypedException;
import org.mule.runtime.api.message.Error;
import org.mule.runtime.api.message.ErrorType;
import org.mule.runtime.core.privileged.exception.EventProcessingException;

/**
 * Decides whether a failure raised by the wrapped processor chain counts as a circuit failure
 * (ADR-001: only configured "infrastructure" error types count; business/validation errors never do).
 *
 * <p>A Mule error type is looked up by walking the given {@link Throwable}'s cause chain. What the
 * {@code Chain.process} error callback actually hands back, confirmed by inspecting the real throwable in
 * a deployed flow rather than assumed, is a
 * {@link org.mule.runtime.core.privileged.exception.MessagingException} (or another
 * {@link EventProcessingException} subtype, e.g. {@code InterceptionException} when a processor is
 * intercepted) — never a bare {@link TypedException} at the top. The error type lives on the
 * {@code Event} that exception carries ({@link EventProcessingException#getEvent()}{@code .getError()}),
 * which is the runtime's supported, public-contract way to read it
 * ({@link org.mule.runtime.api.event.Event#getError()}, {@link Error#getErrorType()}) — only the one step
 * of getting from the exception to that event needs
 * {@code org.mule.runtime.core.privileged.exception}, the runtime's own supported extension point for
 * this, not an internal package. A direct {@link TypedException} is still checked first, for a processor
 * that raises one without an enclosing {@code MessagingException} (this is how the unit tests in this
 * project construct failures directly, and is a legitimate shape in its own right).
 *
 * <p>A configured type matches either exactly or as an ancestor of the raised type, so listing a parent
 * type (e.g. {@code MULE:CONNECTIVITY}) also covers its subtypes.
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
      if (current instanceof EventProcessingException) {
        Optional<Error> error = ((EventProcessingException) current).getEvent().getError();
        if (error.isPresent()) {
          return error.get().getErrorType();
        }
      }
      current = current.getCause();
    }
    return null;
  }

  private static String formatIdentifier(ErrorType errorType) {
    return errorType.getNamespace() + ":" + errorType.getIdentifier();
  }
}
