package md.utm.telecom.evidence.repository;

import md.utm.telecom.evidence.model.DetectionEvidence;
import md.utm.telecom.shared.persistence.InsertOperations;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface DetectionEvidenceRepository
        extends Repository<DetectionEvidence, String>, InsertOperations<DetectionEvidence> {

    Optional<DetectionEvidence> findById(String detectionId);

    Optional<DetectionEvidence> findByEpisodeIdAndSequence(String episodeId, long sequence);

    @Modifying
    @Query(value = """
            INSERT INTO app.detection_evidence (
                detection_id, episode_id, sequence, phase, service, scope_id,
                window_start, window_end, detected_at, payload
            ) VALUES (
                :detectionId, :episodeId, :sequence, :phase, :service, :scopeId,
                :windowStart, :windowEnd, :detectedAt, CAST(:payload AS jsonb)
            )
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("detectionId") String detectionId,
            @Param("episodeId") String episodeId,
            @Param("sequence") long sequence,
            @Param("phase") String phase,
            @Param("service") String service,
            @Param("scopeId") String scopeId,
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("detectedAt") Instant detectedAt,
            @Param("payload") String payload);

    Page<DetectionEvidence> findByEpisodeIdOrderBySequenceAsc(
            String episodeId, Pageable pageable);
}