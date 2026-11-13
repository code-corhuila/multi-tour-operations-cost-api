package co.edu.corhuila.multi_tour.operations_cost.app;

import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which operations of the contract were asked and answered well, a 2xx, at least once. An operation that was
 * only asked with a bad token or a bad body has not been seen working.
 */
final class ExercisedOperations {

  private final Set<String> answeredWell = ConcurrentHashMap.newKeySet();

  void record(String method, String pathTemplate, int status) {
    if (status >= 200 && status < 300) {
      answeredWell.add(method + " " + pathTemplate);
    }
  }

  /** The operations in {@code all} that no test has seen answered with a 2xx. */
  Set<String> missing(Set<String> all) {
    Set<String> missing = new TreeSet<>(all);
    missing.removeAll(answeredWell);
    return missing;
  }
}
