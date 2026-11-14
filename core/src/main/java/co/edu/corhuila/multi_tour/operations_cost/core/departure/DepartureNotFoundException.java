package co.edu.corhuila.multi_tour.operations_cost.core.departure;

/** There is no such departure for this tenant; another tenant's departure is the same as none. The HTTP adapter makes it a 404. */
public class DepartureNotFoundException extends RuntimeException {

  public DepartureNotFoundException(String code) {
    super("There is no departure " + code);
  }
}
