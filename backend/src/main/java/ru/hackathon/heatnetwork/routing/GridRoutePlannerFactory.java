package ru.hackathon.heatnetwork.routing;

import java.util.Optional;
import ru.hackathon.heatnetwork.model.Dataset;
import ru.hackathon.heatnetwork.model.Model.Evaluation;
import ru.hackathon.heatnetwork.model.Model.RouteCandidate;
import ru.hackathon.heatnetwork.model.Model.SearchOptions;
import ru.hackathon.heatnetwork.routing.RoutePlanner.SearchSession;

/**
 * Factory implementing the module contract: {@link RoutePlanner#open} starts a new
 * search session backed by {@link GridRoutePlanner} for the given Dataset.
 */
public final class GridRoutePlannerFactory implements RoutePlanner {

    private final RulesCatalog catalog;

    public GridRoutePlannerFactory(RulesCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public SearchSession open(Dataset dataset, SearchOptions options) {
        GridRoutePlanner planner = new GridRoutePlanner(dataset, options, catalog);
        return new SearchSession() {
            @Override public Optional<RouteCandidate> next() {
                return planner.next();
            }
            @Override public void feedback(Evaluation evaluation) {
                planner.feedback(evaluation);
            }
            @Override public void close() {
                planner.close();
            }
        };
    }
}
