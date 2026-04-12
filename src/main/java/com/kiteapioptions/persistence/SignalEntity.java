package com.kiteapioptions.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import java.time.Instant;

@Entity
public class SignalEntity {

    @Id
    @GeneratedValue
    private Long id;
    private Instant timestamp;
    private String underlying;
    private String signalType;
    private String reason;

    protected SignalEntity() {
    }

    public SignalEntity(Instant timestamp, String underlying, String signalType, String reason) {
        this.timestamp = timestamp;
        this.underlying = underlying;
        this.signalType = signalType;
        this.reason = reason;
    }

    public Long getId() { return id; }
    public Instant getTimestamp() { return timestamp; }
    public String getUnderlying() { return underlying; }
    public String getSignalType() { return signalType; }
    public String getReason() { return reason; }
}
