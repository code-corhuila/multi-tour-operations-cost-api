package co.edu.corhuila.multi_tour.operations_cost.adapters.catalog;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.Catalog;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.CatalogTour;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DependencyUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * The catalog of the tenant, read through the API of commercial-reservation, which owns it (ADR-004 D4). It forwards the
 * credential of the person who is asking, because that route opens to the roles of a person and not to the token of a
 * service (decision D1 of spec 002); the catalog judges the tenant from that token, so it answers only this tenant's items.
 * One try with a limit of time (a person is waiting): a catalog that does not answer, answers late, refuses or answers
 * something the contract does not say is "not available", and the cause goes to the log, never to the message.
 */
public final class HttpCatalog implements Catalog {

  private static final Logger LOG = LoggerFactory.getLogger(HttpCatalog.class);
  private static final String ITEM = "/api/v1/commercial-reservation/catalog/services/{serviceId}";

  /** What the request in course says about who asks: the Authorization header to forward and the correlation id. */
  public interface Credentials {
    String authorization();

    String correlationId();
  }

  private final RestClient client;
  private final ObjectMapper json = new ObjectMapper();
  private final Credentials credentials;

  public HttpCatalog(String baseUrl, Duration timeout, Credentials credentials) {
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalStateException("COMMERCIAL_RESERVATION_BASE_URL is required: http://host:port of commercial-reservation");
    }
    this.client = OutboundHttp.client(baseUrl, timeout);
    this.credentials = credentials;
  }

  @Override
  public Optional<CatalogTour> tour(UUID serviceId) {
    try {
      return client.get()
          .uri(ITEM, serviceId)
          .header("Authorization", credentials.authorization())
          .header("X-Correlation-Id", credentials.correlationId())
          .header("Accept", "application/json")
          .exchange((request, response) -> {
            int status = response.getStatusCode().value();
            if (status == 404) {
              return Optional.<CatalogTour>empty();
            }
            if (status != 200) {
              LOG.warn("The catalog answered {} for the item {}", status, serviceId);
              throw new DependencyUnavailableException("catalog");
            }
            return Optional.of(read(serviceId, json.readTree(response.getBody())));
          });
    } catch (DependencyUnavailableException unavailable) {
      throw unavailable;
    } catch (RuntimeException failure) {
      // the class only: the message of a parser or of a connection may quote the body or the address
      LOG.warn("The catalog could not be read for the item {}: {}", serviceId, failure.getClass().getSimpleName());
      throw new DependencyUnavailableException("catalog");
    }
  }

  private static CatalogTour read(UUID serviceId, JsonNode item) {
    CatalogTour.Type type = switch (text(item, "serviceType")) {
      case "TOUR" -> CatalogTour.Type.TOUR;
      case "TRANSPORTATION" -> CatalogTour.Type.TRANSPORTATION;
      default -> throw new IllegalStateException("unknown serviceType");
    };
    boolean active = switch (text(item, "catalogStatus")) {
      case "ACTIVE" -> true;
      case "INACTIVE" -> false;
      default -> throw new IllegalStateException("unknown catalogStatus");
    };
    // the contract requires the field and lets it be null (an item with no capacity of its own); anything else is not what it says
    JsonNode capacity = item.get("configuredCapacity");
    if (capacity == null || !(capacity.isNull() || capacity.isInt() && capacity.asInt() >= 0)) {
      throw new IllegalStateException("configuredCapacity is not what the contract says");
    }
    // absent is "no transport"; present, it must be a boolean
    JsonNode transport = item.get("transportRequired");
    if (transport != null && !transport.isNull() && !transport.isBoolean()) {
      throw new IllegalStateException("transportRequired is not what the contract says");
    }
    // the operational cost the catalog parametrizes: absent or null is a tour without one; present, it must be an integer (HU-OPS-007)
    JsonNode cost = item.get("baseOperationalCostCents");
    if (cost != null && !cost.isNull() && !(cost.isIntegralNumber() && cost.canConvertToLong())) {
      throw new IllegalStateException("baseOperationalCostCents is not what the contract says");
    }
    Optional<Long> catalogued = cost == null || cost.isNull() ? Optional.empty() : Optional.of(cost.asLong());
    return new CatalogTour(serviceId, type, active, capacity.isNull() ? 0 : capacity.asInt(), transport != null && transport.asBoolean(), catalogued);
  }

  private static String text(JsonNode item, String field) {
    JsonNode node = item.get(field);
    if (node == null || !node.isTextual()) {
      throw new IllegalStateException(field + " is missing");
    }
    return node.asText();
  }
}
