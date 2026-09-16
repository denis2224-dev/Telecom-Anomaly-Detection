package md.utm.telecom.incidents.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "analysts", schema = "app")
public class Analyst {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String issuer;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String subject;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Analyst() {
    }

    public Analyst(String issuer, String subject, String displayName) {
        this.issuer = requireNonBlank(issuer, "issuer");
        this.subject = requireNonBlank(subject, "subject");
        rename(displayName);
    }

    @PrePersist
    private void beforeInsert() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    private void beforeUpdate() {
        updatedAt = Instant.now();
    }

    public void rename(String displayName) {
        String value = requireNonBlank(displayName, "displayName");

        if (value.length() > 120) {
            throw new IllegalArgumentException(
                    "displayName must not exceed 120 characters");
        }

        this.displayName = value;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public UUID getId() {
        return id;
    }

    public String getIssuer() {
        return issuer;
    }

    public String getSubject() {
        return subject;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }

        return value;
    }
}