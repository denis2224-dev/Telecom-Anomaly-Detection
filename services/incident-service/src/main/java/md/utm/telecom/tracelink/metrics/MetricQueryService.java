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
    private final ReferenceMetricRollupRepository reference;

    public MetricQueryService(MetricDefinitionRegistry definitions,
                              MetricRollupRepository repository, MetricScopeRepository scopes, ReferenceMetricRollupRepository reference) {
        this.definitions = definitions;
        this.repository = repository;
        this.scopes = scopes;
        this.reference = reference;
    }

    public MetricSeriesResponse series(TraceQuery query) {
        if (query.pointCount() > 8641) {
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
        String catalogueTopology = scopes.resolve(query).topologyVersion();
        if (reference.imported(query.datasetId())) {
            if (query.metric().equals("roaming.activeOutbound") && !query.scopeId().endsWith(":out")
                    || query.metric().equals("roaming.activeInbound") && !query.scopeId().endsWith(":in"))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Snapshot direction does not match its population");
            var points = reference.series(query, definition);
            return new MetricSeriesResponse(query.datasetId(), query.domain(), query.metric(), query.scopeType(), query.scopeId(),
                    query.resolution(), query.from(), query.to(), query.effectiveFrom(), query.effectiveTo(),
                    !query.from().equals(query.effectiveFrom()), !query.to().equals(query.effectiveTo()), 300,
                    definition.unit(), definition.aggregation(), definition.policyVersion(), catalogueTopology, dataset.synthetic(), null, points);
        }
        if (!query.metric().equals("voice.success_rate") && !query.metric().equals("volte.setup.success"))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The full validated reference archive is not projected");
        if (query.metric().equals("volte.setup.success")) {
            var points = repository.primitiveRatio(query, definition.policyVersion(), catalogueTopology);
            return new MetricSeriesResponse(query.datasetId(), query.domain(), query.metric(),
                    query.scopeType(), query.scopeId(), query.resolution(), query.from(), query.to(),
                    query.effectiveFrom(), query.effectiveTo(),
                    !query.from().equals(query.effectiveFrom()),
                    !query.to().equals(query.effectiveTo()), 300, definition.unit(),
                    definition.aggregation(), definition.policyVersion(), catalogueTopology,
                    dataset.synthetic(), null, points);
        }
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
            if (!shape.topologyVersion().equals(catalogueTopology)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Metric source topology differs from registered catalogue");
            }
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
