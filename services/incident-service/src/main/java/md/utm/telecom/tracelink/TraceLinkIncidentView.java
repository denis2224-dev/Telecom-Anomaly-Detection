package md.utm.telecom.tracelink;

import java.time.Instant;
import tools.jackson.databind.JsonNode;

/** Read-only technical incident view; the nested detection remains the immutable wire envelope. */
public record TraceLinkIncidentView(String episodeId, Instant firstObservedAt, Instant detectedAt,
        Instant latestWindowEnd, long latestSequence, String technicalState, String severity,
        JsonNode latestDetection) { }
