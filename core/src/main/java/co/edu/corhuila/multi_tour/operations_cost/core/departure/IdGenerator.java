package co.edu.corhuila.multi_tour.operations_cost.core.departure;

import java.util.UUID;

/** Port: new identifiers, so the use case does not depend on chance. */
public interface IdGenerator {

  UUID next();
}
