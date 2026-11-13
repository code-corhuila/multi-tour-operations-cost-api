package co.edu.corhuila.multi_tour.operations_cost.adapters.http;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reuses the {@code X-Correlation-Id} of the caller when it is safe to write in a log line, otherwise
 * creates one; returns it, puts it in the MDC so every log line carries it and offers it as the
 * {@code traceId} of the error envelope. It also runs on the error dispatch, with the id of the first
 * pass, so a failure outside the controllers (another filter, the container) is answered with the same id.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Correlation-Id";
  private static final String MDC_KEY = "traceId";
  private static final Logger LOG = LoggerFactory.getLogger(CorrelationIdFilter.class);
  private static final int LOGGED_PATH = 200;
  private static final String ATTRIBUTE = CorrelationIdFilter.class.getName() + ".id";
  private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,128}");

  /** The trace id of the request being served; the filter always runs first, so "unknown" is a bug to see. */
  static String current() {
    String id = MDC.get(MDC_KEY);
    return id != null ? id : "unknown";
  }

  @Override
  protected boolean shouldNotFilterErrorDispatch() {
    return false;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String id = (String) request.getAttribute(ATTRIBUTE);
    if (id == null) {
      String sent = request.getHeader(HEADER);
      id = sent != null && SAFE.matcher(sent).matches() ? sent : UUID.randomUUID().toString();
      request.setAttribute(ATTRIBUTE, id);
    }
    MDC.put(MDC_KEY, id);
    response.setHeader(HEADER, id);
    boolean completed = false;
    try {
      chain.doFilter(request, response);
      completed = true;
    } finally {
      // an exception that leaves the chain is answered 500 by the container after this line
      int status = completed ? response.getStatus() : 500;
      if (request.getDispatcherType() != DispatcherType.ERROR) {
        LOG.info("{} {} answered {}", request.getMethod(), cut(request.getRequestURI()), status);
      }
      MDC.remove(MDC_KEY);
    }
  }

  private static String cut(String path) {
    return path.length() > LOGGED_PATH ? path.substring(0, LOGGED_PATH) + "..." : path;
  }
}
