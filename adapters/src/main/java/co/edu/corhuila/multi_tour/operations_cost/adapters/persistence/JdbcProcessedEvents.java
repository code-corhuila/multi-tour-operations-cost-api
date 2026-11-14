package co.edu.corhuila.multi_tour.operations_cost.adapters.persistence;

import co.edu.corhuila.multi_tour.operations_cost.core.security.ProcessedEvents;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The ids of the events already applied; a repeat writes nothing. */
public final class JdbcProcessedEvents implements ProcessedEvents {

  private final JdbcClient jdbc;

  public JdbcProcessedEvents(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean markIfNew(UUID eventId) {
    return jdbc.sql("INSERT INTO processed_event (event_id) VALUES (:id) ON CONFLICT (event_id) DO NOTHING").param("id", eventId).update() == 1;
  }
}
