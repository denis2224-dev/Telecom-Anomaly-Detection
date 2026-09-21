package md.utm.telecom.processing.ingestion;

import java.util.Objects;

/** Immutable raw delivery; null payload represents a Kafka tombstone. */
public record ObservationDelivery(byte[] payload, String key, String topic, int partition, long offset) {
    public ObservationDelivery {
        payload = payload == null ? null : payload.clone();
        Objects.requireNonNull(topic, "topic");
        if (partition < 0 || offset < 0) throw new IllegalArgumentException("Invalid Kafka coordinates");
    }
    @Override public byte[] payload() { return payload == null ? null : payload.clone(); }
}
