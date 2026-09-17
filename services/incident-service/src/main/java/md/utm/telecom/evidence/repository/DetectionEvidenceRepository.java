package md.utm.telecom.evidence.repository;

import md.utm.telecom.evidence.model.DetectionEvidence;
import md.utm.telecom.shared.persistence.InsertOperations;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.util.Optional;

public interface DetectionEvidenceRepository
        extends Repository<DetectionEvidence, String>, InsertOperations<DetectionEvidence> {

    Optional<DetectionEvidence> findById(String detectionId);

    Optional<DetectionEvidence> findByEpisodeIdAndSequence(String episodeId, long sequence);

    Page<DetectionEvidence> findByEpisodeIdOrderBySequenceAsc(String episodeId, Pageable pageable);
}
