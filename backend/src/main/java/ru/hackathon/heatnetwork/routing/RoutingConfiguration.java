package ru.hackathon.heatnetwork.routing;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.hackathon.heatnetwork.model.Dataset;
import ru.hackathon.heatnetwork.model.Model.SearchOptions;

/**
 * Wires module 2 implementations. The coordinator (participant 1) opens a search
 * session per job; Spring injects {@link GridRoutePlanner} as the {@link RoutePlanner}
 * and {@link DefaultSpatialValidator} as the {@link SpatialValidator}.
 *
 * <p>GridRoutePlanner implements RoutePlanner directly but needs a RulesCatalog;
 * it is created through {@link #routePlanner(Dataset, SearchOptions, RulesCatalog)}
 * when the coordinator supplies the per-job Dataset.</p>
 */
@Configuration
public class RoutingConfiguration {

    @Bean
    public RulesCatalog rulesCatalog() {
        return RulesCatalog.loadDefault();
    }

    @Bean
    public SpatialValidator spatialValidator(RulesCatalog catalog) {
        return new DefaultSpatialValidator(catalog);
    }

    @Bean
    public RoutePlanner routePlanner(RulesCatalog catalog) {
        return new GridRoutePlannerFactory(catalog);
    }
}
