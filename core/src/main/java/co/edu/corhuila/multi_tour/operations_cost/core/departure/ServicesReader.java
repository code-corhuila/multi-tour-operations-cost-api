package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Joins the reservations of a departure, which commercial-reservation owns, with what this service recorded for each. When the owner
 * cannot answer, the departure is still answered without them (api 006 D-04), so the page of a departure does not fall with it.
 */
public final class ServicesReader {

  private final Reservations reservations;
  private final ServiceRecords records;
  private final ExtraordinaryCancellations cancellations;

  public ServicesReader(Reservations reservations, ServiceRecords records, ExtraordinaryCancellations cancellations) {
    this.reservations = reservations;
    this.records = records;
    this.cancellations = cancellations;
  }

  /** The reservations of a departure; throws {@link DependencyUnavailableException} when their owner cannot say. */
  public List<ReservationOfDeparture> reservationsOf(Departure departure) {
    return reservations.reservationsOf(departure.serviceId(), departure.id());
  }

  public Optional<ServicesView> read(Departure departure) {
    List<ReservationOfDeparture> held;
    try {
      held = reservationsOf(departure);
    } catch (DependencyUnavailableException unavailable) {
      return Optional.empty();
    }
    return Optional.of(join(held, records.recordedFor(departure.tenantId(), departure.id()), cancellations.cancellationsOf(departure.tenantId(), departure.id())));
  }

  /** The same join over reservations that were already read, so a save judges and answers from one reading. */
  public ServicesView join(List<ReservationOfDeparture> held, List<ServiceRecord> recorded, List<ExtraordinaryCancellation> cancelled) {
    Map<UUID, ServiceRecord> byReservation = recorded.stream().collect(Collectors.toMap(ServiceRecord::reservationId, Function.identity(), (first, again) -> first));
    Map<UUID, ExtraordinaryCancellation> cancelledBy = cancelled.stream().collect(Collectors.toMap(ExtraordinaryCancellation::reservationId, Function.identity(), (first, again) -> first));
    List<ServicesView.ReservationView> views = held.stream().map(r -> new ServicesView.ReservationView(
        r, Optional.ofNullable(byReservation.get(r.reservationId())), Optional.ofNullable(cancelledBy.get(r.reservationId())))).toList();
    int base = ServiceRecord.realBase(views.stream().flatMap(v -> v.recorded().stream()).toList());
    return new ServicesView(views, base);
  }
}
