package com.kiteapioptions.persistence;

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
    private String message;

    protected ErrorEventEntity() {
    }

    public ErrorEventEntity(Instant timestamp, String component, String message) {
        this.timestamp = timestamp;
        this.component = component;
        this.message = message;
    }

    public Long getId() { return id; }
    public Instant getTimestamp() { return timestamp; }
    public String getComponent() { return component; }
    public String getMessage() { return message; }
}
