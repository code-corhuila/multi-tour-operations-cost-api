package co.edu.corhuila.multi_tour.operations_cost.app;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

/** The composition root: the one place that knows every piece and builds them. */
@SpringBootApplication
@ComponentScan("co.edu.corhuila.multi_tour.operations_cost")
class CompositionRoot {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  public static void main(String[] args) {
    SpringApplication.run(CompositionRoot.class, args);
  }
}
