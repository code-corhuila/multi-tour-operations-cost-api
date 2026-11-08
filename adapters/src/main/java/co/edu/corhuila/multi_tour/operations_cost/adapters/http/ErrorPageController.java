package co.edu.corhuila.multi_tour.operations_cost.adapters.http;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the container dispatches to when a failure happens outside MVC (a filter that throws, a request
 * the server refuses): the same envelope and the same trace id, in place of Boot's own JSON or an HTML page.
 */
@RestController
class ErrorPageController implements ErrorController {

  private static final Logger LOG = LoggerFactory.getLogger(ErrorPageController.class);

  @RequestMapping("/error")
  ResponseEntity<ErrorBody> error(HttpServletRequest request) {
    // Reached by the container with the status of the failure; reached by a client it is just a route that is not there
    Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
    HttpStatus status = code instanceof Integer value ? HttpStatus.resolve(value) : HttpStatus.NOT_FOUND;
    status = status != null ? status : HttpStatus.INTERNAL_SERVER_ERROR;
    Object cause = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
    if (cause instanceof Throwable failure) {
      if (status.is5xxServerError()) {
        LOG.error("Failure outside the controllers", failure);
      } else {
        LOG.warn("Request refused outside the controllers: {}", failure.getClass().getSimpleName());
      }
    }
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .body(ErrorBody.of(status, CorrelationIdFilter.current()));
  }
}
