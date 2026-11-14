package co.edu.corhuila.multi_tour.operations_cost.adapters.http;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.BusinessRuleViolationException;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DepartureNotFoundException;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DependencyUnavailableException;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.InvalidDataException;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.InvalidStatusTransitionException;
import co.edu.corhuila.multi_tour.operations_cost.core.outbox.OutboxEventNotFoundException;
import co.edu.corhuila.multi_tour.operations_cost.core.security.CallerNotAllowedException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The only place that turns a failure inside MVC into a status and the envelope. Every exception that
 * Spring already knows how to rank (bad parameter 400, wrong method 405, media types 415 and 406, no
 * route 404) keeps its own status; anything else is a 500 whose detail goes to the log, never to the caller.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
  /** A field of the body is named back only if it is written like one: the text is the caller's. */
  private static final Pattern FIELD = Pattern.compile("[A-Za-z0-9_]{1,64}");

  /** A body field that is not declared or has the wrong type is named; any other unreadable body keeps the plain 400. */
  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    if (e.getCause() instanceof JsonMappingException json && !json.getPath().isEmpty()) {
      String field = json.getPath().get(json.getPath().size() - 1).getFieldName();
      if (field != null && FIELD.matcher(field).matches()) {
        String what = json instanceof UnrecognizedPropertyException ? "is not declared" : "has the wrong type";
        return answer(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "The request is not valid",
            List.of(new ErrorBody.Detail(field, what)));
      }
    }
    return super.handleHttpMessageNotReadable(e, headers, status, request);
  }
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception e, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    if (status.is5xxServerError()) {
      LOG.error("Unexpected failure", e);
    }
    HttpHeaders answer = new HttpHeaders();
    answer.putAll(headers);
    answer.setContentType(MediaType.APPLICATION_JSON);
    return new ResponseEntity<>(ErrorBody.of(status, CorrelationIdFilter.current()), answer, status);
  }

  /** What the core refuses is told in the words the core wrote for the caller; none of them carries an internal detail. */
  @ExceptionHandler(InvalidDataException.class)
  ResponseEntity<Object> invalidData(InvalidDataException e) {
    return answer(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "The request is not valid",
        e.field() == null ? null : List.of(new ErrorBody.Detail(e.field(), e.getMessage())));
  }

  @ExceptionHandler(BusinessRuleViolationException.class)
  ResponseEntity<Object> businessRule(BusinessRuleViolationException e) {
    return answer(HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_RULE_VIOLATION", e.getMessage(), null);
  }

  @ExceptionHandler(InvalidStatusTransitionException.class)
  ResponseEntity<Object> invalidTransition(InvalidStatusTransitionException e) {
    return answer(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_STATUS_TRANSITION", e.getMessage(), null);
  }

  /** Another tenant's departure is the same as none: the answer does not tell them apart. */
  @ExceptionHandler(DepartureNotFoundException.class)
  ResponseEntity<Object> departureNotFound(DepartureNotFoundException e) {
    return answer(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage(), null);
  }

  @ExceptionHandler(OutboxEventNotFoundException.class)
  ResponseEntity<Object> eventNotFound(OutboxEventNotFoundException e) {
    return answer(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage(), null);
  }

  @ExceptionHandler(CallerNotAllowedException.class)
  ResponseEntity<Object> callerNotAllowed(CallerNotAllowedException e) {
    return answer(HttpStatus.FORBIDDEN, "FORBIDDEN", e.getMessage(), null);
  }

  /** Another domain did not answer well; the cause went to the log where it happened. */
  @ExceptionHandler(DependencyUnavailableException.class)
  ResponseEntity<Object> unavailable(DependencyUnavailableException e) {
    return answer(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "A service this operation needs is not available; try again", null);
  }

  private ResponseEntity<Object> answer(HttpStatus status, String code, String message, List<ErrorBody.Detail> details) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return new ResponseEntity<>(new ErrorBody(code, message, details, CorrelationIdFilter.current()), headers, status);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Object> unexpected(Exception e, WebRequest request) {
    return handleExceptionInternal(
        e, null, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
  }
}
