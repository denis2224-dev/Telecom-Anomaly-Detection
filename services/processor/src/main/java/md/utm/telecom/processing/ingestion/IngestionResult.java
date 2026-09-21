package md.utm.telecom.processing.ingestion;

public record IngestionResult(Status status, RejectionReason reason) {
    public enum Status { ACCEPTED, DUPLICATE, REJECTED }
    public static IngestionResult accepted() { return new IngestionResult(Status.ACCEPTED, null); }
    public static IngestionResult duplicate() { return new IngestionResult(Status.DUPLICATE, null); }
    public static IngestionResult rejected(RejectionReason reason) { return new IngestionResult(Status.REJECTED, reason); }
}
