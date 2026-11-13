package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A departure: the date on which a tour really happens (RN-SAL-001). It belongs to one tenant and one tour of its catalog,
 * defines a start and an estimated end, is born {@code SCHEDULED} and changes its date only while it is. Immutable: a change
 * returns the new value. The capacity is not here, it belongs to commercial-reservation (ADR-004 D4). {@code scheduledBy} is
 * the account that scheduled it (the subject of its token, a UUID, as the database stores it); the time zone is an IANA name.
 * The clock is the caller's: one {@code now} per request, never read here.
 */
public record Departure(
    UUID id,
    UUID tenantId,
    String code,
    UUID serviceId,
    Instant start,
    Instant estimatedEnd,
    String timeZone,
    DepartureStatus status,
    UUID scheduledBy,
    Instant scheduledAt) {

  private static final Pattern CODE = Pattern.compile("SAL-[0-9]{3,6}");

  /** Every way of building a departure, the reading of a stored one included, checks what the engine also checks. */
  public Departure {
    // the database keeps microseconds: what is answered the first time is what is read back later
    start = start == null ? null : start.truncatedTo(ChronoUnit.MICROS);
    estimatedEnd = estimatedEnd == null ? null : estimatedEnd.truncatedTo(ChronoUnit.MICROS);
    scheduledAt = scheduledAt == null ? null : scheduledAt.truncatedTo(ChronoUnit.MICROS);
    require(id != null, "id");
    require(tenantId != null, "tenantId");
    require(code != null && CODE.matcher(code).matches(), "code");
    require(serviceId != null, "serviceId");
    require(start != null, "scheduledStart");
    require(estimatedEnd != null, "scheduledEnd");
    require(!estimatedEnd.isBefore(start), "scheduledEnd");
    require(timeZone != null && timeZone.length() <= 64 && ZoneId.getAvailableZoneIds().contains(timeZone), "timeZone");
    require(status != null, "status");
    require(scheduledBy != null, "scheduledBy");
    require(scheduledAt != null, "scheduledAt");
  }

  /** A new departure: {@code SCHEDULED}, with a start that has not begun (decision D3 of spec 002). */
  public static Departure schedule(
      UUID id, UUID tenantId, String code, UUID serviceId, Instant start, Instant estimatedEnd, String timeZone,
      UUID scheduledBy, Instant now) {
    Objects.requireNonNull(now, "now");
    Departure departure = new Departure(id, tenantId, code, serviceId, start, estimatedEnd, timeZone,
        DepartureStatus.SCHEDULED, scheduledBy, now);
    departure.requireNotBegun(start, now);
    return departure;
  }

  /**
   * The same departure with another start and end, while it is {@code SCHEDULED}. Whether it has reservations is the use
   * case's to know. The state is judged first, so a departure that is not SCHEDULED refuses even the same values; setting
   * what is already set on a SCHEDULED one returns this one, unchanged, without looking at the clock.
   */
  public Departure reschedule(Instant newStart, Instant newEnd, Instant now) {
    Objects.requireNonNull(now, "now");
    if (status != DepartureStatus.SCHEDULED) {
      throw new InvalidStatusTransitionException(status, "be rescheduled");
    }
    if (hasSchedule(newStart, newEnd)) {
      return this;
    }
    Departure changed = new Departure(id, tenantId, code, serviceId, newStart, newEnd, timeZone, status, scheduledBy, scheduledAt);
    changed.requireNotBegun(newStart, now);
    return changed;
  }

  public boolean hasSchedule(Instant otherStart, Instant otherEnd) {
    return start.equals(otherStart) && estimatedEnd.equals(otherEnd);
  }

  private void requireNotBegun(Instant candidate, Instant now) {
    if (candidate.isBefore(now)) {
      throw new BusinessRuleViolationException("A departure cannot start in the past");
    }
  }

  private static void require(boolean condition, String field) {
    if (!condition) {
      throw new InvalidDataException(field, "The field " + field + " is missing or not valid");
    }
  }
}
