package md.utm.telecom.evidence.service;

public record IngestResult(
        Disposition disposition,
        int appliedCount,
        long latestSequence
) {}
