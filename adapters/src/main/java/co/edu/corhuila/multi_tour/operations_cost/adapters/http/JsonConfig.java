package co.edu.corhuila.multi_tour.operations_cost.adapters.http;

import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** A number or a boolean is not a text: Jackson would write it as one, and the contract says {@code type: string}. */
@Configuration
class JsonConfig {
  @Bean
  Jackson2ObjectMapperBuilderCustomizer textsAreOnlyTexts() {
    return builder -> builder.postConfigurer(mapper -> java.util.stream.Stream.of(
        CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean)
        .forEach(shape -> mapper.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail)));
  }
}
