package co.edu.corhuila.multi_tour.operations_cost.adapters.catalog;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.DependencyUnavailableException;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.ReservationOfDeparture;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.ReservationState;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Reservations;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
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
  private static final String RESERVATIONS =
      "/api/v1/commercial-reservation/catalog/services/{serviceId}/departures/{departureId}/reservations?page={page}&limit=100";
  private static final int MAX_RESERVATION_PAGES = 20;

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
    Seats[] found = new Seats[1];
    walk(serviceId, row -> {
      if (!departureId.toString().equals(row.path("departureId").asText())) {
        return false;
      }
      found[0] = seats(row);
      return true;
    });
    return Optional.ofNullable(found[0]);
  }

  @Override
  public Map<UUID, Seats> seatsOfTour(UUID serviceId) {
    Map<UUID, Seats> found = new HashMap<>();
    // a partial map would make a departure on a page that was not read look like one that has no row, so the walk never ends early
    boolean wholeList = walk(serviceId, row -> {
      found.put(UUID.fromString(row.path("departureId").asText()), seats(row));
      return false;
    });
    // a tour that disappears half way is a tour that is not there, not a list of the pages already read
    return wholeList ? Map.copyOf(found) : Map.of();
  }

  /**
   * The reservations of a departure, read page by page from the route commercial-reservation made for this service (HU-OPS-004, api 006
   * S6-04), with the credential of the caller. Never partial: more than {@code MAX_RESERVATION_PAGES} pages of 100, a departure it has no
   * row of (404), any other status or an answer that is not what the contract says is "not available". The name of the customer is read
   * and never logged.
   */
  @Override
  public List<ReservationOfDeparture> reservationsOf(UUID serviceId, UUID departureId) {
    try {
      List<ReservationOfDeparture> found = new ArrayList<>();
      for (int page = 1; page <= MAX_RESERVATION_PAGES; page++) {
        JsonNode body = read(RESERVATIONS, serviceId, departureId, page);
        JsonNode rows = body == null ? null : body.get("data");
        JsonNode totalPages = body == null ? null : body.path("meta").get("totalPages");
        if (rows == null || !rows.isArray() || totalPages == null || !totalPages.isInt()) {
          throw new IllegalStateException("the list is not what the contract says");
        }
        for (JsonNode row : rows) {
          found.add(reservation(row));
        }
        if (page >= totalPages.asInt()) {
          return List.copyOf(found);
        }
      }
      LOG.warn("The departure {} has more than {} pages of reservations", departureId, MAX_RESERVATION_PAGES);
    } catch (RuntimeException failure) {
      if (failure instanceof DependencyUnavailableException unavailable) {
        throw unavailable;
      }
      LOG.warn("The reservations of the departure {} could not be read: {}", departureId, failure.getClass().getSimpleName());
    }
    throw new DependencyUnavailableException("reservations");
  }

  /**
   * Walks the pages of the list of departures of a tour (the only place that pages it), offering each row to {@code stopAt}, which says
   * whether the walk has what it came for. It answers false when the tour is not there (or stops being there half way) and true
   * when the list ended or the walk stopped; more than
   * {@code MAX_PAGES} pages is "not available" and not "not there", because the row looked for may be on a page that was not read.
   */
  private boolean walk(UUID serviceId, Predicate<JsonNode> stopAt) {
    try {
      for (int page = 1; page <= MAX_PAGES; page++) {
        JsonNode body = read(serviceId, page);
        if (body == null) {
          return false;
        }
        JsonNode rows = body.get("data");
        JsonNode totalPages = body.path("meta").get("totalPages");
        if (rows == null || !rows.isArray() || totalPages == null || !totalPages.isInt()) {
          throw new IllegalStateException("the list is not what the contract says");
        }
        for (JsonNode row : rows) {
          if (stopAt.test(row)) {
            return true;
          }
        }
        if (page >= totalPages.asInt()) {
          return true;
        }
      }
      LOG.warn("The tour {} has more than {} pages of departures", serviceId, MAX_PAGES);
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
    return read(LIST, serviceId, page);
  }

  private JsonNode read(String uri, Object... variables) {
    return client.get()
        .uri(uri, variables)
        .header("Authorization", credentials.authorization())
        .header("X-Correlation-Id", credentials.correlationId())
        .header("Accept", "application/json")
        .exchange((request, response) -> {
          int status = response.getStatusCode().value();
          if (status == 404) {
            return null;
          }
          if (status != 200) {
            LOG.warn("commercial-reservation answered {} to {} for the tour {}", status, uri.substring(0, uri.indexOf('?')), variables[0]);
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

  /** The words of commercial-reservation (ReservationStatus) in the words of this service; a state this service does not know is a failure. */
  private static ReservationState state(String status) {
    return switch (status) {
      case "PENDING_PAYMENT" -> ReservationState.NOT_ELIGIBLE;
      case "CONFIRMED" -> ReservationState.ELIGIBLE;
      case "IN_EXECUTION" -> ReservationState.IN_EXECUTION;
      case "COMPLETED" -> ReservationState.FINISHED;
      case "CANCELLED" -> ReservationState.CANCELLED;
      default -> throw new IllegalStateException("the state of a reservation is not what the contract says");
    };
  }

  private static ReservationOfDeparture reservation(JsonNode row) {
    JsonNode name = row.get("customerName");
    JsonNode people = row.get("people");
    JsonNode status = row.get("reservationStatus");
    if (name == null || !name.isTextual() || people == null || !people.isInt() || people.asInt() < 1 || status == null || !status.isTextual()) {
      throw new IllegalStateException("the reservation is not what the contract says");
    }
    return new ReservationOfDeparture(UUID.fromString(row.path("reservationId").asText()), name.asText(), people.asInt(), state(status.asText()));
  }
}
