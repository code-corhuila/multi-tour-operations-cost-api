package co.edu.corhuila.multi_tour.operations_cost.adapters.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.DependencyUnavailableException;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.Reservations;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The list of departures of a tour of commercial-reservation as a stub of its published contract (listDepartures, PROPOSED):
 * the adapter pages it looking for one departure and says how many seats it holds (RN-OPS-API-11, -17; decision D2).
 */
class HttpReservationsTest {

  private static final UUID TOUR = UUID.fromString("00000000-0000-4000-8000-0000000000bb");
  private static final UUID DEPARTURE = UUID.fromString("00000000-0000-4000-8000-000000000001");
  private static final UUID OTHER = UUID.fromString("00000000-0000-4000-8000-000000000002");

  private HttpServer server;
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private final AtomicReference<String> correlation = new AtomicReference<>();
  private final List<String> queries = new CopyOnWriteArrayList<>();
  private final List<String> pages = new ArrayList<>();
  private volatile int status = 200;
  private volatile long delayMillis;
  private HttpReservations reservations;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      correlation.set(exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
      String query = exchange.getRequestURI().getRawPath() + "?" + exchange.getRequestURI().getRawQuery();
      queries.add(query);
      try {
        Thread.sleep(delayMillis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      int page = 1;
      String raw = exchange.getRequestURI().getRawQuery();
      if (raw != null && raw.contains("page=")) {
        page = Integer.parseInt(raw.replaceAll(".*page=(\\d+).*", "$1"));
      }
      String body = status == 200 && page <= pages.size() ? pages.get(page - 1) : "";
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
      if (bytes.length > 0) {
        exchange.getResponseBody().write(bytes);
      }
      exchange.close();
    });
    server.start();
    reservations = new HttpReservations("http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofMillis(400),
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

  private static String row(UUID id, int total, int available) {
    return "{\"departureId\":\"" + id + "\",\"startsAt\":\"2026-12-01T13:00:00Z\",\"availableSeats\":" + available
        + ",\"totalSeats\":" + total + ",\"full\":" + (available == 0) + ",\"extra\":1}";
  }

  private static String page(int number, int totalPages, String... rows) {
    return "{\"data\":[" + String.join(",", rows) + "],\"meta\":{\"page\":" + number + ",\"limit\":100,\"total\":" + rows.length
        + ",\"totalPages\":" + totalPages + "}}";
  }

  @Test
  void A_the_seats_held_are_the_total_minus_the_available() {
    pages.add(page(1, 1, row(OTHER, 10, 10), row(DEPARTURE, 12, 9)));
    assertThat(reservations.seatsOf(TOUR, DEPARTURE)).contains(new Reservations.Seats(12, 3));
  }

  @Test
  void A_a_departure_with_nothing_held_has_zero() {
    pages.add(page(1, 1, row(DEPARTURE, 12, 12)));
    assertThat(reservations.seatsOf(TOUR, DEPARTURE)).contains(new Reservations.Seats(12, 0));
  }

  @Test
  void A_it_looks_on_the_next_pages_until_it_finds_the_departure() {
    pages.add(page(1, 2, row(OTHER, 10, 10)));
    pages.add(page(2, 2, row(DEPARTURE, 12, 8)));
    assertThat(reservations.seatsOf(TOUR, DEPARTURE)).contains(new Reservations.Seats(12, 4));
    assertThat(queries).hasSize(2);
  }

  @Test
  void A_a_departure_that_is_not_in_the_list_is_empty() {
    pages.add(page(1, 1, row(OTHER, 10, 10)));
    assertThat(reservations.seatsOf(TOUR, DEPARTURE)).isEmpty();
  }

  @Test
  void A_a_tour_that_commercial_reservation_does_not_have_is_empty() {
    status = 404;
    assertThat(reservations.seatsOf(TOUR, DEPARTURE)).isEmpty();
  }

  @Test
  void A_it_forwards_the_credential_the_correlation_id_and_asks_the_right_path_with_the_largest_page() {
    pages.add(page(1, 1, row(DEPARTURE, 12, 12)));
    reservations.seatsOf(TOUR, DEPARTURE);
    assertThat(authorization.get()).isEqualTo("Bearer the-token-of-the-caller");
    assertThat(correlation.get()).isEqualTo("trace-42");
    assertThat(queries.get(0)).isEqualTo("/api/v1/commercial-reservation/catalog/services/" + TOUR + "/departures?page=1&limit=100");
  }

  @Test
  void A_after_five_pages_without_finding_it_the_answer_is_not_available_and_not_empty() {
    for (int i = 1; i <= 6; i++) {
      pages.add(page(i, 6, row(OTHER, 10, 10)));
    }
    assertThatThrownBy(() -> reservations.seatsOf(TOUR, DEPARTURE)).isInstanceOf(DependencyUnavailableException.class);
    assertThat(queries).hasSize(5);
  }

  @Test
  void A_every_other_status_is_not_available_and_says_nothing_of_the_cause() {
    for (int failing : new int[] {400, 401, 403, 429, 500, 503}) {
      status = failing;
      assertThatThrownBy(() -> reservations.seatsOf(TOUR, DEPARTURE)).as("status " + failing)
          .isInstanceOf(DependencyUnavailableException.class)
          .hasMessageNotContaining("127.0.0.1").hasMessageNotContaining("token");
    }
  }

  @Test
  void A_a_slow_or_absent_commercial_reservation_is_not_available() {
    pages.add(page(1, 1, row(DEPARTURE, 12, 12)));
    delayMillis = 1500;
    long began = System.nanoTime();
    assertThatThrownBy(() -> reservations.seatsOf(TOUR, DEPARTURE)).isInstanceOf(DependencyUnavailableException.class);
    assertThat(Duration.ofNanos(System.nanoTime() - began)).isLessThan(Duration.ofMillis(1200));
    server.stop(0);
    assertThatThrownBy(() -> reservations.seatsOf(TOUR, DEPARTURE)).isInstanceOf(DependencyUnavailableException.class);
  }

  @Test
  void A_an_answer_that_is_not_what_the_contract_says_is_not_available() {
    for (String bad : new String[] {"not json", "{}", "[1]", "{\"data\":[{\"departureId\":\"" + DEPARTURE + "\"}],\"meta\":{\"totalPages\":1}}",
        "{\"data\":[" + row(DEPARTURE, 5, 9) + "],\"meta\":{\"totalPages\":1}}",
        "{\"data\":[" + row(DEPARTURE, 5, -1) + "],\"meta\":{\"totalPages\":1}}",
        "{\"data\":[" + row(DEPARTURE, 5, 5) + "]}"}) {
      pages.clear();
      pages.add(bad);
      assertThatThrownBy(() -> reservations.seatsOf(TOUR, DEPARTURE)).as(bad).isInstanceOf(DependencyUnavailableException.class);
    }
  }

  @Test
  void A_a_redirect_is_not_followed() throws IOException {
    java.util.concurrent.atomic.AtomicInteger hitsElsewhere = new java.util.concurrent.atomic.AtomicInteger();
    HttpServer elsewhere = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    elsewhere.createContext("/", exchange -> {
      hitsElsewhere.incrementAndGet();
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    elsewhere.start();
    server.removeContext("/");
    server.createContext("/", exchange -> {
      exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + elsewhere.getAddress().getPort() + "/stolen");
      exchange.sendResponseHeaders(302, -1);
      exchange.close();
    });
    try {
      assertThatThrownBy(() -> reservations.seatsOf(TOUR, DEPARTURE)).isInstanceOf(DependencyUnavailableException.class);
      assertThat(hitsElsewhere).hasValue(0);
    } finally {
      elsewhere.stop(0);
    }
  }

  // api 004 D-06: the list of departures reads each tour once, so the seats of every departure of a tour come from one paged read.
  @Test
  void A_the_seats_of_every_departure_of_a_tour_come_from_one_paged_read() {
    pages.add(page(1, 2, row(OTHER, 10, 10)));
    pages.add(page(2, 2, row(DEPARTURE, 12, 9)));
    assertThat(reservations.seatsOfTour(TOUR))
        .containsOnly(java.util.Map.entry(OTHER, new Reservations.Seats(10, 0)), java.util.Map.entry(DEPARTURE, new Reservations.Seats(12, 3)));
    assertThat(queries).hasSize(2);
  }

  @Test
  void A_a_tour_that_commercial_reservation_does_not_have_has_no_seats() {
    status = 404;
    assertThat(reservations.seatsOfTour(TOUR)).isEmpty();
  }

  @Test
  void A_a_tour_with_more_than_five_pages_is_not_available_because_the_answer_would_be_partial() {
    for (int i = 1; i <= 6; i++) {
      pages.add(page(i, 6, row(UUID.randomUUID(), 10, 10)));
    }
    assertThatThrownBy(() -> reservations.seatsOfTour(TOUR)).isInstanceOf(DependencyUnavailableException.class);
  }

  @Test
  void A_the_read_of_a_whole_tour_fails_like_the_read_of_one_departure() {
    for (int failing : new int[] {401, 500, 503}) {
      status = failing;
      assertThatThrownBy(() -> reservations.seatsOfTour(TOUR)).as("status " + failing).isInstanceOf(DependencyUnavailableException.class);
    }
    status = 200;
    pages.add("{\"data\":[" + row(DEPARTURE, 5, 9) + "],\"meta\":{\"totalPages\":1}}");
    assertThatThrownBy(() -> reservations.seatsOfTour(TOUR)).isInstanceOf(DependencyUnavailableException.class);
  }
}
