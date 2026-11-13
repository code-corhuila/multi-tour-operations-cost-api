package co.edu.corhuila.multi_tour.operations_cost.app;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter;
import io.restassured.filter.Filter;
import io.restassured.filter.FilterContext;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The contract of the service, {@code openapi/openapi.yaml} (ADR-004 D7). Every answer that goes through
 * {@link #filter()} is validated against it, and the operation that was asked is remembered with its status, so
 * that the gate ({@code ContractGateTest}) fails if an operation of the contract was never seen answered well.
 */
final class Contract {

  private static final String SPEC = Path.of("..", "openapi", "openapi.yaml").toUri().toString();
  /** The request and the answer, but not the token: the 401 tests send none on purpose. */
  private static final OpenApiValidationFilter FULL = new OpenApiValidationFilter(
      OpenApiInteractionValidator.createFor(SPEC)
          .withLevelResolver(LevelResolver.create()
              .withLevel("validation.request.security.missing", ValidationReport.Level.IGNORE).build())
          .build());
  /** Only the answer: a test of a refusal sends a request that breaks the contract, on purpose. */
  private static final OpenApiValidationFilter ANSWERS = new OpenApiValidationFilter(
      OpenApiInteractionValidator.createFor(SPEC)
          .withLevelResolver(LevelResolver.create()
              .withLevel("validation.request", ValidationReport.Level.IGNORE).build())
          .build());
  private static final Set<String> OPERATIONS = read();
  private static final ExercisedOperations EXERCISED = new ExercisedOperations();

  private Contract() {}

  /** Validates the request and the answer against the contract and remembers the operation and its status. */
  static Filter filter() {
    return recording(FULL);
  }

  /** Validates only the answer against the contract, for the requests that break it on purpose. */
  static Filter answers() {
    return recording(ANSWERS);
  }

  private static Filter recording(OpenApiValidationFilter validation) {
    return (FilterableRequestSpecification request, FilterableResponseSpecification response, FilterContext context) -> {
      Response answer = validation.filter(request, response, context);
      EXERCISED.record(request.getMethod(), template(request.getDerivedPath()), answer.statusCode());
      return answer;
    };
  }

  /** Every operation of the contract, as {@code METHOD /path/{template}}. */
  static Set<String> operations() {
    return OPERATIONS;
  }

  /** What the gate reports: the operations of the contract that no test has seen answered with a 2xx. */
  static Set<String> unexercised() {
    return EXERCISED.missing(OPERATIONS);
  }

  private static Set<String> read() {
    OpenAPI api = new OpenAPIV3Parser().read(SPEC);
    Set<String> all = new TreeSet<>();
    api.getPaths().forEach((path, item) ->
        item.readOperationsMap().keySet().forEach(method -> all.add(method.name() + " " + path)));
    return all;
  }

  /** The path of the contract that a real path belongs to: an exact one first, then {@code /tenants/{id}}. */
  private static String template(String path) {
    Set<String> paths = new TreeSet<>();
    OPERATIONS.forEach(o -> paths.add(o.substring(o.indexOf(' ') + 1)));
    if (paths.contains(path)) {
      return path;
    }
    for (String candidate : paths) {
      String regex = String.join("[^/]+", java.util.Arrays.stream(candidate.split("\\{[^/}]+}", -1)).map(Pattern::quote).toList());
      if (Pattern.matches(regex, path)) {
        return candidate;
      }
    }
    return path;
  }
}
