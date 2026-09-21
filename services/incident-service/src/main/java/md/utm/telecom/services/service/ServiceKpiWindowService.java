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
            assertExactReplay(message);
        }
        return inserted == 1;
    }

    private void assertExactReplay(ServiceKpiWindowMessage incoming) {
        ServiceKpiWindow existing = windows.findById(incoming.windowId())
                .orElseGet(() -> windows
                        .findByServiceAndScopeIdAndWindowStartAndFeatureVersionAndBaselineVersionAndTopologyVersion(
                                incoming.service(), incoming.scopeId(), incoming.windowStart(), 2,
                                incoming.baselineVersion(), incoming.topologyVersion())
                        .orElseThrow(() -> new IllegalStateException(
                                "KPI insert conflicted without a visible stored row")));

        if (!existing.getWindowId().equals(incoming.windowId())
                || !json.readTree(existing.getPayload())
                .equals(json.readTree(incoming.canonicalPayload()))) {
            throw new IllegalArgumentException(
                    "windowId or versioned KPI identity was reused with different content");
        }
    }
}