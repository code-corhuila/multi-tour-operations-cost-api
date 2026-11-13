package co.edu.corhuila.multi_tour.operations_cost.adapters.security;

import java.util.Locale;

/**
 * Which routes need a token: everything except the health route. A path written in a way that hides where it goes
 * (dots, encoded characters, semicolons, doubled slashes) needs one too, so it is never trusted to decide anything.
 */
final class RouteGuard {

  private RouteGuard() {}

  static boolean isDisguised(String uri) {
    String path = uri.toLowerCase(Locale.ROOT);
    return path.contains("..") || path.contains("/.") || path.contains("%") || path.contains(";") || path.contains("//");
  }

  static boolean needsToken(String uri) {
    if (uri.equals("/health")) {
      return false;
    }
    return isDisguised(uri) || uri.toLowerCase(Locale.ROOT).contains("/api/");
  }
}
