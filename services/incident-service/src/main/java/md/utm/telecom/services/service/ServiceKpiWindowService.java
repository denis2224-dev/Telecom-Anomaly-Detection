package md.utm.telecom.services.service;

import md.utm.telecom.services.messaging.ServiceKpiWindowMessage;
import md.utm.telecom.services.model.ServiceKpiWindow;
import md.utm.telecom.services.repository.ServiceKpiWindowRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class ServiceKpiWindowService {
    private final ServiceKpiWindowRepository windows;
    private final ObjectMapper json;

    public ServiceKpiWindowService(
            ServiceKpiWindowRepository windows,
            ObjectMapper json
    ) {
        this.windows = windows;
        this.json = json;
    }

    @Transactional
    public boolean ingest(String kafkaKey, String rawPayload) {
        return persist(kafkaKey, rawPayload, false);
    }

    /** Explicit initial demo import retains valid prior evidence, including reported gaps/faults. */
    @Transactional
    public boolean ingestBootstrap(String kafkaKey, String rawPayload) {
        return persist(kafkaKey, rawPayload, true);
    }

    private boolean persist(String kafkaKey, String rawPayload, boolean bootstrap) {
        ServiceKpiWindowMessage message =
                ServiceKpiWindowMessage.parse(json, rawPayload);
        if (!message.scopeId().equals(kafkaKey)) {
            throw new IllegalArgumentException("Kafka key must equal scopeId");
        }

        int inserted = windows.insertIfAbsent(
                message.windowId(),
                message.service().name(),
                message.scopeId(),
                message.windowStart(),
                message.windowEnd(),
                message.baselineVersion(),
                message.topologyVersion(),
                message.quality().name(),
                message.canonicalPayload());

        if (inserted == 0) {
            assertReplay(message, bootstrap);
        }
        return inserted == 1;
    }

    private void assertReplay(ServiceKpiWindowMessage incoming, boolean bootstrap) {
        ServiceKpiWindow existing = windows.findById(incoming.windowId())
                .orElseGet(() -> windows
                        .findByServiceAndScopeIdAndWindowStartAndFeatureVersionAndBaselineVersionAndTopologyVersion(
                                incoming.service(), incoming.scopeId(), incoming.windowStart(), 2,
                                incoming.baselineVersion(), incoming.topologyVersion())
                        .orElseThrow(() -> new IllegalStateException(
                                "KPI insert conflicted without a visible stored row")));

        var stored = ServiceKpiWindowMessage.parse(json, existing.getPayload());
        boolean sameIdentity = existing.getWindowId().equals(incoming.windowId()) && stored.windowId().equals(incoming.windowId())
                && stored.scopeId().equals(incoming.scopeId()) && stored.service().equals(incoming.service())
                && stored.windowStart().equals(incoming.windowStart()) && stored.windowEnd().equals(incoming.windowEnd())
                && stored.baselineVersion().equals(incoming.baselineVersion()) && stored.topologyVersion().equals(incoming.topologyVersion());
        if (!sameIdentity || (!bootstrap && !json.readTree(existing.getPayload())
                .equals(json.readTree(incoming.canonicalPayload())))) {
            throw new IllegalArgumentException(
                    "windowId or versioned KPI identity was reused with different content");
        }
    }
}
