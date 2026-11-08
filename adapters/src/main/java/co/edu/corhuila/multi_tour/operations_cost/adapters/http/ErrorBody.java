package co.edu.corhuila.multi_tour.operations_cost.adapters.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.springframework.http.HttpStatusCode;

/** The single error envelope of every answer that is not a success (norm 5.3, Annex C). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorBody(String error, String message, List<Detail> details, String traceId) {

  public record Detail(String field, String message) {}

  /** The envelope of an HTTP status; the codes are written here, not taken from a library enum that can change. */
  public static ErrorBody of(HttpStatusCode status, String traceId) {
    int value = status.value();
    return switch (value) {
      case 400 -> new ErrorBody("VALIDATION_ERROR", "The request is not valid", null, traceId);
      case 401 -> new ErrorBody("UNAUTHORIZED", "A valid token is required", null, traceId);
      case 403 -> new ErrorBody("FORBIDDEN", "The token does not allow this", null, traceId);
      case 404 -> new ErrorBody("NOT_FOUND", "There is no such route", null, traceId);
      case 405 -> new ErrorBody("METHOD_NOT_ALLOWED", "The method is not allowed here", null, traceId);
      case 406 -> new ErrorBody("NOT_ACCEPTABLE", "That representation is not offered", null, traceId);
      case 413 -> new ErrorBody("PAYLOAD_TOO_LARGE", "The request is too large", null, traceId);
      case 415 -> new ErrorBody("UNSUPPORTED_MEDIA_TYPE", "That media type is not accepted", null, traceId);
      case 429 -> new ErrorBody("TOO_MANY_REQUESTS", "Too many requests", null, traceId);
      default ->
          value >= 500
              ? new ErrorBody("INTERNAL_ERROR", "Internal error", null, traceId)
              : new ErrorBody("VALIDATION_ERROR", "The request cannot be served", null, traceId);
    };
  }
}
