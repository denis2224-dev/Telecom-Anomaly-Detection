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

    public MetricQueryService(MetricDefinitionRegistry definitions,
                              MetricRollupRepository repository) {
        this.definitions = definitions;
        this.repository = repository;
    }

    public MetricSeriesResponse series(TraceQuery query) {
        if (query.pointCount() > 8640 || !query.scopeType().equals("CITY")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unsupported scope or too many buckets");
        }
        var definition = definitions.require(query.domain(), query.metric());
        var dataset = repository.dataset(query.datasetId()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown dataset"));
        if (dataset.sourceResolutionSeconds() != 300
                || query.from().isBefore(dataset.from()) || query.to().isAfter(dataset.to())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Query is outside available five-minute history");
        }
        String catalogueTopology = repository.cityTopology(query.datasetId(), query.scopeId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unknown city scope for dataset"));
        var sourceShapes = repository.sourceShapes(query);
        if (sourceShapes.size() > 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Mixed metric versions or units in requested range");
        }
        String topologyVersion = catalogueTopology;
        if (!sourceShapes.isEmpty()) {
            var shape = sourceShapes.getFirst();
            if (!shape.policyVersion().equals(definition.policyVersion())
                    || !shape.aggregation().equals(definition.aggregation())
                    || !shape.unit().equals(definition.unit())
                    || shape.sourceResolutionSeconds() != 300) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Metric source contract mismatch");
            }
            topologyVersion = shape.topologyVersion();
        }
        var points = repository.ratio(query, definition.policyVersion(), topologyVersion);
        if (points.size() != query.pointCount()) {
            throw new IllegalStateException("Metric rollup did not return every requested bucket");
        }
        return new MetricSeriesResponse(query.datasetId(), query.domain(), query.metric(),
                query.scopeType(), query.scopeId(), query.resolution(), query.from(), query.to(),
                query.effectiveFrom(), query.effectiveTo(),
                !query.from().equals(query.effectiveFrom()),
                !query.to().equals(query.effectiveTo()), 300, definition.unit(),
                definition.aggregation(), definition.policyVersion(), topologyVersion,
                dataset.synthetic(), null, points);
    }
}
