package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.multi_tour.operations_cost.core.outbox.Page;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Caller;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Permission;
import co.edu.corhuila.multi_tour.operations_cost.core.security.Role;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** HU-OPS-003 (api spec 004), AC-API-04.1 to -04.3 and -04.13: listing and reading departures, ports as fakes. */
class ReadDeparturesTest {

  private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
  private static final UUID TENANT = new UUID(0, 0xaa);
  private static final UUID OTHER_TENANT = new UUID(0, 0xab);
  private static final UUID ACCOUNT = new UUID(0, 0xcc);
  private static final UUID TOUR = new UUID(0, 0xbb);
  private static final UUID OTHER_TOUR = new UUID(0, 0xb1);

  private FakeWorld world;
  private ListDepartures list;
  private GetDeparture get;
  private final Caller person =
      new Caller(ACCOUNT.toString(), TENANT.toString(), Set.of(Role.TENANT_ADMINISTRATOR), Set.of(Permission.MANAGE_DEPARTURE_EXECUTION));

  @BeforeEach
  void setUp() {
    world = new FakeWorld();
    world.tours.put(TOUR, new CatalogTour(TOUR, CatalogTour.Type.TOUR, true, 18, false));
    world.tours.put(OTHER_TOUR, new CatalogTour(OTHER_TOUR, CatalogTour.Type.TOUR, true, 6, false));
    DepartureViews views = new DepartureViews(world, world, world);
    list = new ListDepartures(world, views);
    get = new GetDeparture(world, views, new ServicesReader(world, world));
  }

  private Departure put(int n, UUID tour, DepartureStatus status, UUID tenant, int daysFromNow) {
    Instant start = NOW.plus(Duration.ofDays(daysFromNow));
    Departure created = new Departure(new UUID(0, 1000 + n), tenant, String.format("SAL-%03d", n), tour, start, start.plus(Duration.ofHours(8)),
        "America/Bogota", status, ACCOUNT, NOW.minus(Duration.ofDays(30)));
    world.departures.put(created.id(), created);
    return created;
  }

  private Page<DepartureView> page(Optional<DepartureStatus> status, int page, int limit) {
    return list.execute(new ListDepartures.Query(person, status, page, limit));
  }

  @Test
  void A_the_list_is_the_tenants_most_recent_first_with_capacity_and_seats() {
    Departure oldest = put(1, TOUR, DepartureStatus.SCHEDULED, TENANT, 1);
    Departure newest = put(2, TOUR, DepartureStatus.SCHEDULED, TENANT, 9);
    put(3, TOUR, DepartureStatus.SCHEDULED, OTHER_TENANT, 5);
    world.seats.put(newest.id(), new Reservations.Seats(18, 4));
    Page<DepartureView> found = page(Optional.empty(), 1, 20);
    assertThat(found.total()).isEqualTo(2);
    assertThat(found.data()).extracting(v -> v.departure().id()).containsExactly(newest.id(), oldest.id());
    assertThat(found.data().get(0).booked()).isEqualTo(4);
    assertThat(found.data().get(0).capacity()).isEqualTo(18);
    assertThat(found.data().get(1).booked()).isZero();
  }

  @Test
  void A_the_list_reads_each_tour_once_and_not_once_per_departure() {
    put(1, TOUR, DepartureStatus.SCHEDULED, TENANT, 1);
    put(2, TOUR, DepartureStatus.SCHEDULED, TENANT, 2);
    put(3, TOUR, DepartureStatus.SCHEDULED, TENANT, 3);
    put(4, OTHER_TOUR, DepartureStatus.SCHEDULED, TENANT, 4);
    assertThat(page(Optional.empty(), 1, 20).data()).hasSize(4);
    assertThat(world.catalogCalls).isEqualTo(2);
    assertThat(world.reservationCalls).isEqualTo(2);
  }

  @Test
  void A_the_list_pages_and_filters_by_status() {
    put(1, TOUR, DepartureStatus.SCHEDULED, TENANT, 1);
    put(2, TOUR, DepartureStatus.IN_EXECUTION, TENANT, 2);
    put(3, TOUR, DepartureStatus.SCHEDULED, TENANT, 3);
    assertThat(page(Optional.empty(), 2, 2).data()).hasSize(1);
    Page<DepartureView> scheduled = page(Optional.of(DepartureStatus.SCHEDULED), 1, 20);
    assertThat(scheduled.total()).isEqualTo(2);
    assertThat(scheduled.data()).allSatisfy(v -> assertThat(v.departure().status()).isEqualTo(DepartureStatus.SCHEDULED));
    assertThat(page(Optional.of(DepartureStatus.CANCELLED), 1, 20).data()).isEmpty();
  }

  @Test
  void A_a_limit_or_a_page_out_of_range_is_refused_and_never_trimmed() {
    for (int[] bad : new int[][] {{0, 20}, {-1, 20}, {1, 0}, {1, 101}}) {
      assertThatThrownBy(() -> page(Optional.empty(), bad[0], bad[1])).isInstanceOf(InvalidDataException.class);
    }
    assertThat(page(Optional.empty(), 1, 100).data()).isEmpty();
  }

  @Test
  void A_a_tour_that_left_the_catalog_answers_zero_capacity_and_the_list_still_works() {
    put(1, new UUID(0, 0xee), DepartureStatus.SCHEDULED, TENANT, 1);
    DepartureView view = page(Optional.empty(), 1, 20).data().get(0);
    assertThat(view.tourCapacity()).isZero();
    assertThat(view.capacity()).isZero();
  }

  @Test
  void A_the_list_with_reservations_or_the_catalog_down_is_not_available() {
    put(1, TOUR, DepartureStatus.SCHEDULED, TENANT, 1);
    world.reservationsDown = true;
    assertThatThrownBy(() -> page(Optional.empty(), 1, 20)).isInstanceOf(DependencyUnavailableException.class);
    world.reservationsDown = false;
    world.catalogDown = true;
    assertThatThrownBy(() -> page(Optional.empty(), 1, 20)).isInstanceOf(DependencyUnavailableException.class);
  }

  @Test
  void A_one_departure_is_read_with_its_view_and_one_of_another_tenant_is_not_found() {
    Departure mine = put(1, TOUR, DepartureStatus.SCHEDULED, TENANT, 1);
    put(2, TOUR, DepartureStatus.SCHEDULED, OTHER_TENANT, 1);
    assertThat(get.execute(new GetDeparture.Query(person, "SAL-001")).view().departure()).isEqualTo(mine);
    assertThatThrownBy(() -> get.execute(new GetDeparture.Query(person, "SAL-002"))).isInstanceOf(DepartureNotFoundException.class);
    assertThatThrownBy(() -> get.execute(new GetDeparture.Query(person, "SAL-404"))).isInstanceOf(DepartureNotFoundException.class);
  }

  @Test
  void A_reading_one_departure_with_reservations_down_is_not_available() {
    put(1, TOUR, DepartureStatus.SCHEDULED, TENANT, 1);
    world.reservationsDown = true;
    assertThatThrownBy(() -> get.execute(new GetDeparture.Query(person, "SAL-001"))).isInstanceOf(DependencyUnavailableException.class);
  }
}
