package com.kiteapioptions.execution;

import com.kiteapioptions.config.TradingProperties;
import com.kiteapioptions.domain.ExecutionMode;
import com.kiteapioptions.domain.MarketDataMode;
import com.kiteapioptions.domain.TradingMode;
import com.kiteapioptions.domain.UnderlyingSymbol;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
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

    private final AtomicReference<TradingMode> requestedMode;
    private final AtomicReference<MarketDataMode> marketDataMode;
    private final AtomicReference<ExecutionMode> executionMode;
    private final AtomicReference<EnumSet<UnderlyingSymbol>> enabledUnderlyings;
    private final AtomicReference<RuntimeState> runtimeState =
            new AtomicReference<>(new RuntimeState(false, false, Instant.now()));
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
        RuntimeState previous = runtimeState.getAndUpdate(state ->
                state.killSwitch ? state.withTimestamp(Instant.now()) : new RuntimeState(true, state.killSwitch, Instant.now()));
        RuntimeState current = runtimeState.get();
        Instant timestamp = Instant.now();
        updatedAt.set(timestamp);
        log.info("Trading state started: wasRunning={}, running={}, requestedMode={}, killSwitch={}, updatedAt={}",
                previous.running, current.running, requestedMode.get(), current.killSwitch, current.updatedAt);
    }

    public void stop() {
        RuntimeState previous = runtimeState.getAndUpdate(state -> new RuntimeState(false, state.killSwitch, Instant.now()));
        RuntimeState current = runtimeState.get();
        updatedAt.set(current.updatedAt);
        log.info("Trading state stopped: wasRunning={}, running={}, requestedMode={}, killSwitch={}, updatedAt={}",
                previous.running, current.running, requestedMode.get(), current.killSwitch, current.updatedAt);
    }

    public void enableKillSwitch() {
        RuntimeState previous = runtimeState.getAndUpdate(state -> new RuntimeState(false, true, Instant.now()));
        RuntimeState current = runtimeState.get();
        updatedAt.set(current.updatedAt);
        log.warn("Kill switch enabled: wasKillSwitchEnabled={}, wasRunning={}, running={}, requestedMode={}, updatedAt={}",
                previous.killSwitch, previous.running, current.running, requestedMode.get(), current.updatedAt);
    }

    public void clearKillSwitch() {
        RuntimeState previous = runtimeState.getAndUpdate(state -> new RuntimeState(state.running, false, Instant.now()));
        RuntimeState current = runtimeState.get();
        updatedAt.set(current.updatedAt);
        log.warn("Kill switch cleared: wasKillSwitchEnabled={}, running={}, requestedMode={}, updatedAt={}",
                previous.killSwitch, current.running, requestedMode.get(), current.updatedAt);
    }

    public void setRequestedMode(TradingMode mode) {
        TradingMode previousMode = requestedMode.getAndSet(mode);
        Instant timestamp = Instant.now();
        updatedAt.set(timestamp);
        log.info("Requested trading mode changed: previousMode={}, requestedMode={}, running={}, killSwitch={}, updatedAt={}",
                previousMode, mode, running(), killSwitchEnabled(), timestamp);
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

    public boolean running() { return runtimeState.get().running; }
    public boolean killSwitchEnabled() { return runtimeState.get().killSwitch; }
    public TradingMode requestedMode() { return requestedMode.get(); }
    public MarketDataMode marketDataMode() { return marketDataMode.get(); }
    public ExecutionMode executionMode() { return executionMode.get(); }
    public List<UnderlyingSymbol> enabledUnderlyings() { return List.copyOf(enabledUnderlyings.get()); }
    public Instant updatedAt() { return updatedAt.get(); }

    private record RuntimeState(boolean running, boolean killSwitch, Instant updatedAt) {
        RuntimeState withTimestamp(Instant timestamp) {
            return new RuntimeState(running, killSwitch, timestamp);
        }
    }
}
