package co.edu.corhuila.multi_tour.operations_cost.adapters.catalog;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.DependencyUnavailableException;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Reservations;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * The seats of a departure, read from the list of departures of its tour in commercial-reservation (decision D2 of spec 002;
 * that service has no route that counts the reservations of one departure). The list is paged looking for the departure; its
 * rows carry the total and the available seats, and the held ones are the difference. Same rules as the catalog adapter: the
 * credential of the caller, one try with a limit of time, no redirect, and anything unexpected is "not available" with the
 * cause in the log and never in the message. Five pages of 100 are enough for a tour; if the departure is not in them the
 * answer is "not available", not "not there".
 */
public final class HttpReservations implements Reservations {

  private static final Logger LOG = LoggerFactory.getLogger(HttpReservations.class);
  private static final String LIST = "/api/v1/commercial-reservation/catalog/services/{serviceId}/departures?page={page}&limit=100";
  private static final int MAX_PAGES = 5;

  private final RestClient client;
  private final ObjectMapper json = new ObjectMapper();
  private final HttpCatalog.Credentials credentials;

  public HttpReservations(String baseUrl, Duration timeout, HttpCatalog.Credentials credentials) {
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalStateException("COMMERCIAL_RESERVATION_BASE_URL is required: http://host:port of commercial-reservation");
    }
    this.client = OutboundHttp.client(baseUrl, timeout);
    this.credentials = credentials;
  }

  @Override
  public Optional<Seats> seatsOf(UUID serviceId, UUID departureId) {
    try {
      for (int page = 1; page <= MAX_PAGES; page++) {
        JsonNode body = read(serviceId, page);
        if (body == null) {
          return Optional.empty();
        }
        JsonNode rows = body.get("data");
        JsonNode totalPages = body.path("meta").get("totalPages");
        if (rows == null || !rows.isArray() || totalPages == null || !totalPages.isInt()) {
          throw new IllegalStateException("the list is not what the contract says");
        }
        for (JsonNode row : rows) {
          if (departureId.toString().equals(row.path("departureId").asText())) {
            return Optional.of(seats(row));
          }
        }
        if (page >= totalPages.asInt()) {
          return Optional.empty();
        }
      }
      // the departure may be on a page that was not read: that is not the same as not being there
      LOG.warn("The departure {} is not in the first {} pages of the tour {}", departureId, MAX_PAGES, serviceId);
      throw new DependencyUnavailableException("reservations");
    } catch (DependencyUnavailableException unavailable) {
      throw unavailable;
    } catch (RuntimeException failure) {
      LOG.warn("The reservations could not be read for the tour {}: {}", serviceId, failure.getClass().getSimpleName());
      throw new DependencyUnavailableException("reservations");
    }
  }

  /** One page, or null when the tour is not there; any other status is a failure. */
  private JsonNode read(UUID serviceId, int page) {
    return client.get()
        .uri(LIST, serviceId, page)
        .header("Authorization", credentials.authorization())
        .header("X-Correlation-Id", credentials.correlationId())
        .header("Accept", "application/json")
        .exchange((request, response) -> {
          int status = response.getStatusCode().value();
          if (status == 404) {
            return null;
          }
          if (status != 200) {
            LOG.warn("The list of departures answered {} for the tour {}", status, serviceId);
            throw new DependencyUnavailableException("reservations");
          }
          return json.readTree(response.getBody());
        });
  }

  private static Seats seats(JsonNode row) {
    JsonNode total = row.get("totalSeats");
    JsonNode available = row.get("availableSeats");
    if (total == null || available == null || !total.isInt() || !available.isInt()
        || available.asInt() < 0 || available.asInt() > total.asInt()) {
      throw new IllegalStateException("the seats are not what the contract says");
    }
    return new Seats(total.asInt(), total.asInt() - available.asInt());
  }
}
