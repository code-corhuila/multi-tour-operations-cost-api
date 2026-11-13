package co.edu.corhuila.multi_tour.operations_cost.app;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** A filter that fails on demand: the shell must answer the envelope for what happens outside MVC too. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class BoomFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (request.getHeader("X-Boom-Filter") != null) {
      throw new ServletException("boom secret");
    }
    chain.doFilter(request, response);
  }
}
