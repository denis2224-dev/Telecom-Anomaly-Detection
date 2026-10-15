package md.utm.telecom.tracelink.metrics;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class MetricQueryService {
    private final MetricDefinitionRegistry definitions;
    private final MetricRollupRepository repository;
    private final MetricScopeRepository scopes;
    public MetricQueryService(MetricDefinitionRegistry definitions, MetricRollupRepository repository,
                              MetricScopeRepository scopes) {
        this.definitions = definitions;
        this.repository = repository;
        this.scopes = scopes;
    }
    public MetricSeriesResponse series(TraceQuery q) {
        if (q.pointCount() > 8640) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Too many buckets");
        var definition = definitions.require(q.domain(), q.metric());
        var dataset = repository.dataset(q.datasetId()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown dataset"));
        if (dataset.sourceResolutionSeconds() != 300 || q.from().isBefore(dataset.from()) || q.to().isAfter(dataset.to())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Query is outside available five-minute history");
        }
        var scope = scopes.resolve(q);
        if (q.metric().equals("volte.setup.success")) {
            var points = repository.primitiveRatio(q, definition.policyVersion(), scope.topologyVersion());
            return new MetricSeriesResponse(q.datasetId(), q.domain(), q.metric(), q.scopeType(), q.scopeId(), q.resolution(),
                    q.from(), q.to(), q.effectiveFrom(), q.effectiveTo(), !q.from().equals(q.effectiveFrom()),
                    !q.to().equals(q.effectiveTo()), 300, definition.unit(), definition.aggregation(),
                    definition.policyVersion(), scope.topologyVersion(), dataset.synthetic(), null, points);
        }
        var shapes = repository.sourceShapes(q, scope);
        if (shapes.size() > 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Mixed metric versions or units");
        if (!shapes.isEmpty()) {
            var shape = shapes.getFirst();
            if (!shape.policyVersion().equals(definition.policyVersion()) || !shape.topologyVersion().equals(scope.topologyVersion())
                    || !shape.aggregation().equals(definition.aggregation()) || !shape.unit().equals(definition.unit())
                    || shape.sourceResolutionSeconds() != 300) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Metric source contract mismatch");
            }
        }
        var points = repository.ratio(q, definition.policyVersion(), scope);
        if (points.size() != q.pointCount()) throw new IllegalStateException("Missing generated query buckets");
        return new MetricSeriesResponse(q.datasetId(), q.domain(), q.metric(), q.scopeType(), q.scopeId(), q.resolution(),
                q.from(), q.to(), q.effectiveFrom(), q.effectiveTo(), !q.from().equals(q.effectiveFrom()),
                !q.to().equals(q.effectiveTo()), 300, definition.unit(), definition.aggregation(),
                definition.policyVersion(), scope.topologyVersion(), dataset.synthetic(), null, points);
    }
}
