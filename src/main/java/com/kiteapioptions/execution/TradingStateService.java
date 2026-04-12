package com.kiteapioptions.execution;

import com.kiteapioptions.domain.TradingMode;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

/**
 * Runtime control state used by REST endpoints and execution gates.
 */
@Service
public class TradingStateService {

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean killSwitch = new AtomicBoolean(false);
    private final AtomicReference<TradingMode> requestedMode = new AtomicReference<>(TradingMode.PAPER);
    private final AtomicReference<Instant> updatedAt = new AtomicReference<>(Instant.now());

    public void start() {
        running.set(true);
        updatedAt.set(Instant.now());
    }

    public void stop() {
        running.set(false);
        updatedAt.set(Instant.now());
    }

    public void enableKillSwitch() {
        killSwitch.set(true);
        running.set(false);
        updatedAt.set(Instant.now());
    }

    public void clearKillSwitch() {
        killSwitch.set(false);
        updatedAt.set(Instant.now());
    }

    public void setRequestedMode(TradingMode mode) {
        requestedMode.set(mode);
        updatedAt.set(Instant.now());
    }

    public boolean running() { return running.get(); }
    public boolean killSwitchEnabled() { return killSwitch.get(); }
    public TradingMode requestedMode() { return requestedMode.get(); }
    public Instant updatedAt() { return updatedAt.get(); }
}
