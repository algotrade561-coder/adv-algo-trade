package com.algo.trade.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import java.time.Instant;

@Entity
public class ErrorEventEntity {

    @Id
    @GeneratedValue
    private Long id;
    private Instant timestamp;
    private String component;
    private String severity;
    private String message;

    protected ErrorEventEntity() {
    }

    /** Legacy constructor — defaults to MEDIUM severity. */
    public ErrorEventEntity(Instant timestamp, String component, String message) {
        this(timestamp, component, "MEDIUM", message);
    }

    public ErrorEventEntity(Instant timestamp, String component, String severity, String message) {
        this.timestamp = timestamp;
        this.component = component;
        this.severity = severity;
        this.message = message;
    }

    public Long getId() { return id; }
    public Instant getTimestamp() { return timestamp; }
    public String getComponent() { return component; }
    public String getSeverity() { return severity; }
    public String getMessage() { return message; }
}
