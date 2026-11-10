package co.edu.corhuila.multi_tour.operations_cost.adapters.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.CatalogTour;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.DependencyUnavailableException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The catalog of commercial-reservation as a stub that answers the shapes of its published contract (getCatalogService):
 * the adapter forwards the credential of the caller, reads the item and turns every failure into "not available" (RN-OPS-API-12, -17).
 */
class HttpCatalogTest {

  private static final UUID TOUR = UUID.fromString("00000000-0000-4000-8000-0000000000bb");
  private static final String PATH = "/api/v1/commercial-reservation/catalog/services/" + TOUR;

  private HttpServer server;
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private final AtomicReference<String> correlation = new AtomicReference<>();
  private final AtomicReference<String> requestedPath = new AtomicReference<>();
  private volatile int status = 200;
  private volatile String body = "";
  private volatile long delayMillis;
  private volatile String redirectTo;
  private HttpCatalog catalog;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      correlation.set(exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
      requestedPath.set(exchange.getRequestURI().getPath());
      try {
        Thread.sleep(delayMillis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      if (redirectTo != null) {
        exchange.getResponseHeaders().add("Location", redirectTo);
      }
      exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
      if (bytes.length > 0) {
        exchange.getResponseBody().write(bytes);
      }
      exchange.close();
    });
    server.start();
    catalog = new HttpCatalog("http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofMillis(400),
        new HttpCatalog.Credentials() {
          @Override
          public String authorization() {
            return "Bearer the-token-of-the-caller";
          }

          @Override
          public String correlationId() {
            return "trace-42";
          }
        });
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private static String item(String type, String catalogStatus, String capacity) {
    return "{\"serviceId\":\"" + TOUR + "\",\"serviceType\":\"" + type + "\",\"commercialName\":\"Canon\","
        + "\"catalogStatus\":\"" + catalogStatus + "\",\"baseRateCents\":35000000,\"configuredCapacity\":" + capacity
        + ",\"somethingNew\":{\"a\":1}}";
  }

  @Test
  void A_an_active_tour_is_read_with_its_capacity_and_unknown_fields_are_ignored() {
    body = item("TOUR", "ACTIVE", "12");
    assertThat(catalog.tour(TOUR)).contains(new CatalogTour(TOUR, CatalogTour.Type.TOUR, true, 12));
  }

  @Test
  void A_a_transport_item_and_an_inactive_one_are_read_as_they_are() {
    body = item("TRANSPORTATION", "ACTIVE", "40");
    assertThat(catalog.tour(TOUR)).contains(new CatalogTour(TOUR, CatalogTour.Type.TRANSPORTATION, true, 40));
    body = item("TOUR", "INACTIVE", "12");
    assertThat(catalog.tour(TOUR)).contains(new CatalogTour(TOUR, CatalogTour.Type.TOUR, false, 12));
  }

  private static String itemWithTransport(String transport) {
    return item("TOUR", "ACTIVE", "12").replace("\"somethingNew\"", "\"transportRequired\":" + transport + ",\"somethingNew\"");
  }

  @Test
  void A_the_transport_flag_is_read_and_a_missing_one_is_false() {
    body = itemWithTransport("true");
    assertThat(catalog.tour(TOUR).orElseThrow().transportRequired()).isTrue();
    body = itemWithTransport("false");
    assertThat(catalog.tour(TOUR).orElseThrow().transportRequired()).isFalse();
    body = item("TOUR", "ACTIVE", "12");
    assertThat(catalog.tour(TOUR).orElseThrow().transportRequired()).isFalse();
  }

  @Test
  void A_a_transport_flag_that_is_not_a_boolean_is_not_available() {
    body = itemWithTransport("\"yes\"");
    assertThatThrownBy(() -> catalog.tour(TOUR)).isInstanceOf(DependencyUnavailableException.class);
  }

  @Test
  void A_a_capacity_that_is_null_is_zero() {
    body = item("TOUR", "ACTIVE", "null");
    assertThat(catalog.tour(TOUR).orElseThrow().capacity()).isZero();
  }

  @Test
  void A_it_forwards_the_credential_and_the_correlation_id_to_the_right_path() {
    body = item("TOUR", "ACTIVE", "12");
    catalog.tour(TOUR);
    assertThat(authorization.get()).isEqualTo("Bearer the-token-of-the-caller");
    assertThat(correlation.get()).isEqualTo("trace-42");
    assertThat(requestedPath.get()).isEqualTo(PATH);
  }

  @Test
  void A_an_item_that_is_not_in_the_catalog_is_empty() {
    status = 404;
    body = "{\"error\":\"NOT_FOUND\",\"message\":\"not found\",\"traceId\":\"x\"}";
    assertThat(catalog.tour(TOUR)).isEmpty();
  }

  @Test
  void A_every_other_status_is_not_available() {
    for (int failing : new int[] {400, 401, 403, 429, 500, 502, 503}) {
      status = failing;
      body = "{\"error\":\"X\",\"message\":\"secret detail\",\"traceId\":\"x\"}";
      assertThatThrownBy(() -> catalog.tour(TOUR)).as("status " + failing)
          .isInstanceOf(DependencyUnavailableException.class)
          .hasMessageNotContaining("secret").hasMessageNotContaining("127.0.0.1").hasMessageNotContaining("token");
    }
  }

  @Test
  void A_a_slow_catalog_is_not_available_and_does_not_wait_longer_than_the_limit() {
    body = item("TOUR", "ACTIVE", "12");
    delayMillis = 1500;
    long began = System.nanoTime();
    assertThatThrownBy(() -> catalog.tour(TOUR)).isInstanceOf(DependencyUnavailableException.class);
    assertThat(Duration.ofNanos(System.nanoTime() - began)).isLessThan(Duration.ofMillis(1200));
  }

  @Test
  void A_a_catalog_that_does_not_answer_is_not_available() {
    server.stop(0);
    assertThatThrownBy(() -> catalog.tour(TOUR)).isInstanceOf(DependencyUnavailableException.class)
        .hasMessageNotContaining("127.0.0.1");
  }

  @Test
  void A_an_answer_that_is_not_what_the_contract_says_is_not_available() {
    for (String bad : new String[] {"not json", "{}", item("SPACESHIP", "ACTIVE", "1"), item("TOUR", "MAYBE", "1"),
        "[1,2]", item("TOUR", "ACTIVE", "\"many\"")}) {
      body = bad;
      assertThatThrownBy(() -> catalog.tour(TOUR)).as(bad).isInstanceOf(DependencyUnavailableException.class);
    }
  }

  @Test
  void A_a_redirect_is_not_followed_and_the_credential_goes_nowhere_else() throws IOException {
    java.util.concurrent.atomic.AtomicInteger hitsElsewhere = new java.util.concurrent.atomic.AtomicInteger();
    HttpServer elsewhere = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    elsewhere.createContext("/", exchange -> {
      hitsElsewhere.incrementAndGet();
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    elsewhere.start();
    try {
      status = 302;
      redirectTo = "http://127.0.0.1:" + elsewhere.getAddress().getPort() + "/stolen";
      assertThatThrownBy(() -> catalog.tour(TOUR)).isInstanceOf(DependencyUnavailableException.class);
      assertThat(hitsElsewhere).hasValue(0);
    } finally {
      elsewhere.stop(0);
    }
  }

  @Test
  void A_a_capacity_that_is_missing_negative_or_not_an_integer_is_not_available() {
    String withoutCapacity = "{\"serviceId\":\"" + TOUR + "\",\"serviceType\":\"TOUR\",\"catalogStatus\":\"ACTIVE\"}";
    for (String bad : new String[] {withoutCapacity, item("TOUR", "ACTIVE", "-1"), item("TOUR", "ACTIVE", "12.5")}) {
      body = bad;
      assertThatThrownBy(() -> catalog.tour(TOUR)).as(bad).isInstanceOf(DependencyUnavailableException.class);
    }
  }

  @Test
  void A_the_base_address_is_required() {
    assertThatThrownBy(() -> new HttpCatalog(" ", Duration.ofSeconds(3), null)).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("COMMERCIAL_RESERVATION_BASE_URL");
  }

  private static String itemWithCost(String cost) {
    return item("TOUR", "ACTIVE", "12").replace("\"somethingNew\"", "\"baseOperationalCostCents\":" + cost + ",\"somethingNew\"");
  }

  @Test
  void A_the_catalogued_cost_is_read_when_it_is_there_and_absent_null_or_not_above_zero_means_none() {
    body = itemWithCost("21000000");
    assertThat(catalog.tour(TOUR).orElseThrow().baseOperationalCostCents()).contains(21_000_000L);
    body = item("TOUR", "ACTIVE", "12");
    assertThat(catalog.tour(TOUR).orElseThrow().baseOperationalCostCents()).isEmpty();
    body = itemWithCost("null");
    assertThat(catalog.tour(TOUR).orElseThrow().baseOperationalCostCents()).isEmpty();
    body = itemWithCost("0");
    assertThat(catalog.tour(TOUR).orElseThrow().baseOperationalCostCents()).isEmpty();
  }

  @Test
  void A_a_catalogued_cost_that_is_not_an_integer_is_not_available() {
    for (String bad : new String[] {"\"21000000\"", "1.5", "true", "{\"a\":1}"}) {
      body = itemWithCost(bad);
      assertThatThrownBy(() -> catalog.tour(TOUR)).isInstanceOf(DependencyUnavailableException.class);
    }
  }
}

