package co.edu.corhuila.multi_tour.operations_cost.adapters.catalog;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The client with which this service reads another domain: a limit of time per connection and per read, and a redirect is never
 * followed, so the credential of the caller goes to the configured address and to no other.
 */
final class OutboundHttp {

  private OutboundHttp() {}

  static RestClient client(String baseUrl, Duration timeout) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
      @Override
      protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
        super.prepareConnection(connection, httpMethod);
        connection.setInstanceFollowRedirects(false);
      }
    };
    factory.setConnectTimeout(timeout);
    factory.setReadTimeout(timeout);
    return RestClient.builder().baseUrl(baseUrl.strip()).requestFactory(factory).build();
  }
}
