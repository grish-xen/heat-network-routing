package ru.hackathon.heatnetwork.calculation;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.hackathon.heatnetwork.routing.RulesCatalog;
import ru.hackathon.heatnetwork.routing.SpatialValidator;

/** Wires module 3: the coordinator injects {@link VariantCalculator}. */
@Configuration
public class CalculationConfiguration {

    @Bean
    public VariantCalculator variantCalculator(RulesCatalog catalog, SpatialValidator spatialValidator) {
        return new DefaultVariantCalculator(catalog, spatialValidator);
    }
}
