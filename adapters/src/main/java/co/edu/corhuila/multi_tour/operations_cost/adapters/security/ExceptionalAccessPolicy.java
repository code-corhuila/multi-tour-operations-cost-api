package co.edu.corhuila.multi_tour.operations_cost.adapters.security;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Spec 012 (G-SEC9-1), RN-SEC-012-02 and 03: the token of an exceptional or emergency access reads only, inside the areas of its scope.
 * Here every public route is {@code OPERATIONS}; the internal routes and any other prefix have no area, so the token never reaches them.
 */
final class ExceptionalAccessPolicy {

  static final String BASE = "/api/v1/operations-cost/";
  static final String OPERATIONS = "OPERATIONS";
  private static final String INTERNAL = BASE + "internal/";
  private static final Pattern IDENTIFIER =
      Pattern.compile("(?<=/)([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|SAL-[0-9]{3,6})(?=/|$)");

  private ExceptionalAccessPolicy() {}

  static Optional<String> areaOf(String method, String uri) {
    if (!"GET".equals(method) || !uri.startsWith(BASE) || uri.startsWith(INTERNAL) || uri.equals(INTERNAL.substring(0, INTERNAL.length() - 1)) || RouteGuard.isDisguised(uri)) {
      return Optional.empty();
    }
    return Optional.of(OPERATIONS);
  }

  /** The route with its identifiers replaced, safe to write in an audit fact. */
  static String template(String uri) {
    return IDENTIFIER.matcher(uri).replaceAll("{id}");
  }
}
