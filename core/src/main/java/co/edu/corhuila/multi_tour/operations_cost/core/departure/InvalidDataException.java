package co.edu.corhuila.multi_tour.operations_cost.core.departure;

/** What the request says is not valid; it names the field, and the HTTP adapter makes it a 400. */
public class InvalidDataException extends RuntimeException {

  private final String field;

  public InvalidDataException(String field, String message) {
    super(message);
    this.field = field;
  }

  public String field() {
    return field;
  }
}
