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
    Instant scheduledAt,
    DepartureExecution execution) {

  private static final Pattern CODE = Pattern.compile("SAL-[0-9]{3,6}");
  private static final int MAX_CAUSE = 1000;

  /** A departure with no execution: the way every one is born, and the reading of one that never ran. */
  public Departure(
      UUID id, UUID tenantId, String code, UUID serviceId, Instant start, Instant estimatedEnd, String timeZone,
      DepartureStatus status, UUID scheduledBy, Instant scheduledAt) {
    this(id, tenantId, code, serviceId, start, estimatedEnd, timeZone, status, scheduledBy, scheduledAt, DepartureExecution.NONE);
  }

  /**
   * Every way of building a departure, the reading of a stored one included, checks the fields it holds. That the execution agrees with
   * the state (a start for a departure that ran, an end for a finished one, an interruption only with its cause) is kept by the engine
   * (the checks of the migration of HU-OPS-003) and by {@code start} and {@code finish}, which only build coherent ones.
   */
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
    require(execution != null, "execution");
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

  /**
   * The same departure, now running, with the real start that a person recorded (RN-EJE-001). The state is judged first; the
   * start is never earlier than the scheduled one and never later than the moment it is recorded.
   */
  public Departure start(Instant at, UUID by, Instant now) {
    Objects.requireNonNull(now, "now");
    if (status != DepartureStatus.SCHEDULED) {
      throw new InvalidStatusTransitionException(status, "be started");
    }
    Instant actualStart = at == null ? null : at.truncatedTo(ChronoUnit.MICROS);
    require(actualStart != null, "startedAt");
    require(by != null, "startedBy");
    if (actualStart.isBefore(start)) {
      throw new BusinessRuleViolationException("A departure cannot start before its scheduled start");
    }
    if (actualStart.isAfter(now)) {
      throw new BusinessRuleViolationException("A departure cannot start in the future");
    }
    return withExecution(DepartureStatus.IN_EXECUTION, execution.started(actualStart, by));
  }

  /**
   * The same departure, now finished, with the real end that a person recorded; a {@code cause} makes it an interruption, which
   * still ends FINISHED, never CANCELLED (PDR K1). The state is judged first; the end is never earlier than the real start and
   * never later than the moment it is recorded.
   */
  public Departure finish(Instant at, UUID by, Instant now, String cause) {
    Objects.requireNonNull(now, "now");
    if (status != DepartureStatus.IN_EXECUTION) {
      throw new InvalidStatusTransitionException(status, "be finished");
    }
    Instant actualEnd = at == null ? null : at.truncatedTo(ChronoUnit.MICROS);
    require(actualEnd != null, "finishedAt");
    require(by != null, "finishedBy");
    require(cause == null || (!cause.isBlank() && cause.length() <= MAX_CAUSE), "cause");
    if (actualEnd.isBefore(execution.actualStart())) {
      throw new BusinessRuleViolationException("A departure cannot end before it started");
    }
    if (actualEnd.isAfter(now)) {
      throw new BusinessRuleViolationException("A departure cannot end in the future");
    }
    return withExecution(DepartureStatus.FINISHED, execution.finished(actualEnd, by, cause));
  }

  private Departure withExecution(DepartureStatus newStatus, DepartureExecution newExecution) {
    return new Departure(id, tenantId, code, serviceId, start, estimatedEnd, timeZone, newStatus, scheduledBy, scheduledAt, newExecution);
  }

  /** The alert of RN-EJE-001: still scheduled when its scheduled start has arrived. Derived for each answer, never stored. */
  public boolean startAlert(Instant now) {
    return status == DepartureStatus.SCHEDULED && !now.isBefore(start);
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
