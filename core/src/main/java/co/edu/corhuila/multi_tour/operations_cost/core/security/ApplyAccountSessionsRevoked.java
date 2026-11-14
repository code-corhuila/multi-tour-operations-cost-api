package co.edu.corhuila.multi_tour.operations_cost.core.security;

import co.edu.corhuila.multi_tour.operations_cost.core.departure.Transaction;

/**
 * Keeps the cutoff of an account once per event id and never moving it back (HU-SEC-005, api spec 013, RN-OPS-API-130). The id and the
 * cutoff commit together; the id is marked for an older instant too, so the relay stops delivering it.
 */
public final class ApplyAccountSessionsRevoked {

  /** APPLIED is 201; UNCHANGED and ALREADY_PROCESSED are 200 (AC-API-13.2). */
  public enum Outcome { APPLIED, UNCHANGED, ALREADY_PROCESSED }

  private final AccountSessionCutoffs cutoffs;
  private final ProcessedEvents processed;
  private final Transaction transaction;

  public ApplyAccountSessionsRevoked(AccountSessionCutoffs cutoffs, ProcessedEvents processed, Transaction transaction) {
    this.cutoffs = cutoffs;
    this.processed = processed;
    this.transaction = transaction;
  }

  public Outcome execute(AccountSessionsRevoked event) {
    return transaction.run(() -> {
      if (!processed.markIfNew(event.eventId())) {
        return Outcome.ALREADY_PROCESSED;
      }
      return cutoffs.keepLater(new AccountSessionCutoff(event.accountId(), event.tenantId(), event.revokedFrom()))
          ? Outcome.APPLIED : Outcome.UNCHANGED;
    });
  }
}
