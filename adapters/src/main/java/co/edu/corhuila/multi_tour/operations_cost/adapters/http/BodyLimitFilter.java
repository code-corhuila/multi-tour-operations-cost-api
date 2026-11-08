package co.edu.corhuila.multi_tour.operations_cost.adapters.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * No body is read beyond 16 KB (Annex C, explicit limits): the largest of the contract is under 4 KB. A body
 * over the limit is a 413 with the common envelope before anyone parses it; a smaller one is kept and handed on,
 * so a request that declares no length (sent in chunks) is judged by what arrives. It runs after the token filter,
 * so a caller without a token is told so before anything of its body is read; a caller with a token but without
 * the role, or on a route that does not exist, is told 413 first, which says nothing about the route. Only JSON is
 * read by the service: the replay does not serve the parameters of a form (a 415 answers any other type).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class BodyLimitFilter extends OncePerRequestFilter {

  static final int LIMIT = 16 * 1024;
  private static final Set<String> WITH_BODY = Set.of("POST", "PUT", "PATCH", "DELETE");

  private final ObjectMapper mapper;

  BodyLimitFilter(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !WITH_BODY.contains(request.getMethod());
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (request.getContentLengthLong() > LIMIT) {
      refuse(response);
      return;
    }
    byte[] body = request.getInputStream().readNBytes(LIMIT + 1);
    if (body.length > LIMIT) {
      refuse(response);
      return;
    }
    chain.doFilter(new Replay(request, body), response);
  }

  private void refuse(HttpServletResponse response) throws IOException {
    response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
    // the rest of the body is not read, so the connection is not reused for another request
    response.setHeader("Connection", "close");
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    mapper.writeValue(response.getOutputStream(), ErrorBody.of(HttpStatus.PAYLOAD_TOO_LARGE, MDC.get("traceId")));
  }

  /** The same request with the body that was read, so the handler reads it as if nothing had happened. */
  private static final class Replay extends HttpServletRequestWrapper {

    private final byte[] body;

    Replay(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
      ByteArrayInputStream in = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public int read() {
          return in.read();
        }

        @Override
        public int read(byte[] target, int offset, int length) {
          return in.read(target, offset, length);
        }

        @Override
        public int available() {
          return in.available();
        }

        @Override
        public boolean isFinished() {
          return in.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
          throw new IllegalStateException("The body is already read");
        }
      };
    }

    @Override
    public BufferedReader getReader() {
      String name = getCharacterEncoding();
      Charset charset = StandardCharsets.UTF_8;
      try {
        charset = name == null ? charset : Charset.forName(name);
      } catch (IllegalArgumentException unknown) {
        // an unknown charset= in the header is read as UTF-8, as the JSON of the contract is
      }
      return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }
  }
}
