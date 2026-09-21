package md.utm.telecom.observation;

/** Machine-readable validation category, shared by all observation boundaries. */
public final class ObservationValidationException extends IllegalArgumentException {
    public enum Category { SCHEMA_INVALID, SEMANTIC_INVALID, SOURCE_UNAUTHORIZED }
    private final Category category;

    public ObservationValidationException(Category category, String message) {
        super(message);
        this.category = category;
    }

    public Category category() { return category; }
}
