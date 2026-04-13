package com.kiteapioptions.execution;

import com.kiteapioptions.config.TradingProperties;
import com.kiteapioptions.domain.ExecutionMode;
import com.kiteapioptions.domain.MarketDataMode;
import com.kiteapioptions.domain.TradingMode;
import com.kiteapioptions.domain.UnderlyingSymbol;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runtime control state used by REST endpoints and execution gates.
 */
@Service
public class TradingStateService {

    private static final Logger log = LoggerFactory.getLogger(TradingStateService.class);

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean killSwitch = new AtomicBoolean(false);
    private final AtomicReference<TradingMode> requestedMode;
    private final AtomicReference<MarketDataMode> marketDataMode;
    private final AtomicReference<ExecutionMode> executionMode;
    private final AtomicReference<EnumSet<UnderlyingSymbol>> enabledUnderlyings;
    private final AtomicReference<Instant> updatedAt = new AtomicReference<>(Instant.now());

    public TradingStateService(TradingProperties properties) {
        EnumSet<UnderlyingSymbol> configuredUnderlyings = properties.symbols().underlyings().isEmpty()
                ? EnumSet.of(UnderlyingSymbol.NIFTY)
                : EnumSet.copyOf(properties.symbols().underlyings());
        this.enabledUnderlyings = new AtomicReference<>(configuredUnderlyings);
        this.requestedMode = new AtomicReference<>(properties.mode());
        this.marketDataMode = new AtomicReference<>(properties.marketDataMode());
        this.executionMode = new AtomicReference<>(properties.executionMode());
    }

    public void start() {
        boolean wasRunning = running.getAndSet(true);
        Instant timestamp = Instant.now();
        updatedAt.set(timestamp);
        log.info("Trading state started: wasRunning={}, running={}, requestedMode={}, killSwitch={}, updatedAt={}",
                wasRunning, running.get(), requestedMode.get(), killSwitch.get(), timestamp);
    }

    public void stop() {
        boolean wasRunning = running.getAndSet(false);
        Instant timestamp = Instant.now();
        updatedAt.set(timestamp);
        log.info("Trading state stopped: wasRunning={}, running={}, requestedMode={}, killSwitch={}, updatedAt={}",
                wasRunning, running.get(), requestedMode.get(), killSwitch.get(), timestamp);
    }

    public void enableKillSwitch() {
        boolean wasKillSwitchEnabled = killSwitch.getAndSet(true);
        boolean wasRunning = running.getAndSet(false);
        Instant timestamp = Instant.now();
        updatedAt.set(timestamp);
        log.warn("Kill switch enabled: wasKillSwitchEnabled={}, wasRunning={}, running={}, requestedMode={}, updatedAt={}",
                wasKillSwitchEnabled, wasRunning, running.get(), requestedMode.get(), timestamp);
    }

    public void clearKillSwitch() {
        boolean wasKillSwitchEnabled = killSwitch.getAndSet(false);
        Instant timestamp = Instant.now();
        updatedAt.set(timestamp);
        log.warn("Kill switch cleared: wasKillSwitchEnabled={}, running={}, requestedMode={}, updatedAt={}",
                wasKillSwitchEnabled, running.get(), requestedMode.get(), timestamp);
    }

    public void setRequestedMode(TradingMode mode) {
        TradingMode previousMode = requestedMode.getAndSet(mode);
        Instant timestamp = Instant.now();
        updatedAt.set(timestamp);
        log.info("Requested trading mode changed: previousMode={}, requestedMode={}, running={}, killSwitch={}, updatedAt={}",
                previousMode, mode, running.get(), killSwitch.get(), timestamp);
    }

    public void setUnderlyingScanEnabled(UnderlyingSymbol underlying, boolean enabled) {
        EnumSet<UnderlyingSymbol> updatedUnderlyings = enabledUnderlyings.updateAndGet(previous -> {
            EnumSet<UnderlyingSymbol> next = EnumSet.copyOf(previous);
            if (enabled) {
                next.add(underlying);
            } else {
                next.remove(underlying);
            }
            if (next.isEmpty()) {
                next.add(UnderlyingSymbol.NIFTY);
            }
            return next;
        });
        Instant timestamp = Instant.now();
        updatedAt.set(timestamp);
        log.info("Underlying scan state changed: underlying={}, enabled={}, enabledUnderlyings={}, updatedAt={}",
                underlying, enabled, updatedUnderlyings, timestamp);
    }

    public void setRoutingModes(MarketDataMode marketDataMode, ExecutionMode executionMode) {
        MarketDataMode previousMarketDataMode = this.marketDataMode.get();
        ExecutionMode previousExecutionMode = this.executionMode.get();
        if (marketDataMode != null) {
            this.marketDataMode.set(marketDataMode);
        }
        if (executionMode != null) {
            this.executionMode.set(executionMode);
        }
        Instant timestamp = Instant.now();
        updatedAt.set(timestamp);
        log.info("Broker routing modes changed: previousMarketDataMode={}, marketDataMode={}, previousExecutionMode={}, executionMode={}, updatedAt={}",
                previousMarketDataMode, this.marketDataMode.get(), previousExecutionMode, this.executionMode.get(), timestamp);
    }

    public boolean running() { return running.get(); }
    public boolean killSwitchEnabled() { return killSwitch.get(); }
    public TradingMode requestedMode() { return requestedMode.get(); }
    public MarketDataMode marketDataMode() { return marketDataMode.get(); }
    public ExecutionMode executionMode() { return executionMode.get(); }
    public List<UnderlyingSymbol> enabledUnderlyings() { return List.copyOf(enabledUnderlyings.get()); }
    public Instant updatedAt() { return updatedAt.get(); }
}
