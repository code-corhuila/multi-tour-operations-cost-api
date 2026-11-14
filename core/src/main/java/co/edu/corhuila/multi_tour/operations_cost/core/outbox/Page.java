package co.edu.corhuila.multi_tour.operations_cost.core.outbox;

import java.util.List;

/** One page of a listing, with the totals the envelope of the API reports. */
public record Page<T>(List<T> data, int page, int limit, long total) {

  public int totalPages() {
    return (int) ((total + limit - 1) / limit);
  }
}
