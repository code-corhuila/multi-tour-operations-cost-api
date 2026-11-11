package co.edu.corhuila.multi_tour.operations_cost.adapters.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.DependencyUnavailableException;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.ReservationOfDeparture;
import co.edu.corhuila.multi_tour.operations_cost.core.departure.ReservationState;
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
 * The reservations of a departure as a stub of the route of commercial-reservation (listDepartureReservations, PR 231 of that
 * service): the adapter pages it with the credential of the caller and says each reservation in the words of this service (api 006, S6-04).
 */
class HttpDepartureReservationsTest {

  private static final UUID TOUR = UUID.fromString("00000000-0000-4000-8000-0000000000bb");
  private static final UUID DEPARTURE = UUID.fromString("00000000-0000-4000-8000-000000000001");
  private static final UUID R1 = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
  private static final UUID R2 = UUID.fromString("00000000-0000-4000-8000-0000000000a2");

  private HttpServer server;
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private final AtomicReference<String> correlation = new AtomicReference<>();
  private final List<String> queries = new CopyOnWriteArrayList<>();
  private final List<String> pages = new ArrayList<>();
  private volatile int status = 200;
  private HttpReservations reservations;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      correlation.set(exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
      String raw = exchange.getRequestURI().getRawQuery();
      queries.add(exchange.getRequestURI().getRawPath() + "?" + raw);
      int page = raw != null && raw.contains("page=") ? Integer.parseInt(raw.replaceAll(".*page=(\\d+).*", "$1")) : 1;
      byte[] bytes = (status == 200 && page <= pages.size() ? pages.get(page - 1) : "").getBytes(StandardCharsets.UTF_8);
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

  private static String row(UUID id, String name, int people, String reservationStatus) {
    return "{\"reservationId\":\"" + id + "\",\"customerName\":\"" + name + "\",\"people\":" + people
        + ",\"reservationStatus\":\"" + reservationStatus + "\"}";
  }

  private static String page(int number, int totalPages, String... rows) {
    return "{\"data\":[" + String.join(",", rows) + "],\"meta\":{\"page\":" + number + ",\"limit\":100,\"total\":" + rows.length
        + ",\"totalPages\":" + totalPages + "}}";
  }

  @Test
  void A_each_row_is_a_reservation_in_the_words_of_this_service() {
    pages.add(page(1, 1, row(R1, "Ana Perez", 4, "IN_EXECUTION"), row(R2, "Luis Gomez", 2, "PENDING_PAYMENT"),
        row(UUID.randomUUID(), "A", 1, "CONFIRMED"), row(UUID.randomUUID(), "B", 1, "COMPLETED"), row(UUID.randomUUID(), "C", 1, "CANCELLED")));
    assertThat(reservations.reservationsOf(TOUR, DEPARTURE)).extracting(ReservationOfDeparture::state)
        .containsExactly(ReservationState.IN_EXECUTION, ReservationState.NOT_ELIGIBLE, ReservationState.ELIGIBLE,
            ReservationState.FINISHED, ReservationState.CANCELLED);
    assertThat(reservations.reservationsOf(TOUR, DEPARTURE).get(0)).isEqualTo(new ReservationOfDeparture(R1, "Ana Perez", 4, ReservationState.IN_EXECUTION));
  }

  @Test
  void A_the_pages_are_walked_in_order_and_a_departure_without_reservations_is_empty() {
    pages.add(page(1, 2, row(R1, "Ana Perez", 4, "IN_EXECUTION")));
    pages.add(page(2, 2, row(R2, "Luis Gomez", 2, "IN_EXECUTION")));
    assertThat(reservations.reservationsOf(TOUR, DEPARTURE)).extracting(ReservationOfDeparture::reservationId).containsExactly(R1, R2);
    assertThat(queries).hasSize(2);
    pages.clear();
    pages.add(page(1, 1));
    assertThat(reservations.reservationsOf(TOUR, DEPARTURE)).isEmpty();
  }

  @Test
  void A_it_forwards_the_credential_and_asks_the_route_of_the_contract_with_the_largest_page() {
    pages.add(page(1, 1, row(R1, "Ana Perez", 4, "IN_EXECUTION")));
    reservations.reservationsOf(TOUR, DEPARTURE);
    assertThat(authorization.get()).isEqualTo("Bearer the-token-of-the-caller");
    assertThat(correlation.get()).isEqualTo("trace-42");
    assertThat(queries.get(0)).isEqualTo("/api/v1/commercial-reservation/catalog/services/" + TOUR + "/departures/" + DEPARTURE
        + "/reservations?page=1&limit=100");
  }

  @Test
  void A_every_status_that_is_not_200_is_not_available_and_says_nothing_of_the_cause() {
    for (int failing : new int[] {400, 401, 403, 404, 429, 500, 503}) {
      status = failing;
      assertThatThrownBy(() -> reservations.reservationsOf(TOUR, DEPARTURE)).as("status " + failing)
          .isInstanceOf(DependencyUnavailableException.class)
          .hasMessageNotContaining("127.0.0.1").hasMessageNotContaining("token");
    }
  }

  @Test
  void A_an_answer_that_is_not_what_the_contract_says_is_not_available() {
    for (String bad : new String[] {"not json", "{}", "{\"data\":[" + row(R1, "Ana", 4, "IN_EXECUTION") + "]}",
        "{\"data\":[" + row(R1, "Ana", 4, "SOMETHING_NEW") + "],\"meta\":{\"totalPages\":1}}",
        "{\"data\":[" + row(R1, "Ana", 0, "IN_EXECUTION") + "],\"meta\":{\"totalPages\":1}}",
        "{\"data\":[{\"reservationId\":\"not-a-uuid\",\"customerName\":\"Ana\",\"people\":2,\"reservationStatus\":\"CONFIRMED\"}],\"meta\":{\"totalPages\":1}}",
        "{\"data\":[{\"reservationId\":\"" + R1 + "\",\"people\":2,\"reservationStatus\":\"CONFIRMED\"}],\"meta\":{\"totalPages\":1}}"}) {
      pages.clear();
      pages.add(bad);
      assertThatThrownBy(() -> reservations.reservationsOf(TOUR, DEPARTURE)).as(bad).isInstanceOf(DependencyUnavailableException.class);
    }
  }

  @Test
  void A_a_list_too_long_to_read_whole_is_not_available_and_never_partial() {
    for (int i = 1; i <= 21; i++) {
      pages.add(page(i, 21, row(UUID.randomUUID(), "Ana", 1, "CONFIRMED")));
    }
    assertThatThrownBy(() -> reservations.reservationsOf(TOUR, DEPARTURE)).isInstanceOf(DependencyUnavailableException.class);
    assertThat(queries).hasSize(20);
  }
}
