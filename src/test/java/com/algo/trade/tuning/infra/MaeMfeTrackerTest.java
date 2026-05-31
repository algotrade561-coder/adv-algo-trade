package com.algo.trade.tuning.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionInstrument;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.TradeStatus;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.persistence.TradeRepository;
import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.infra.MaeMfeTracker.Direction;
import com.algo.trade.tuning.infra.MaeMfeTracker.EntryContext;
import com.algo.trade.tuning.infra.MaeMfeTracker.Snapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Phase 1, Commit 7 — MAE/MFE tracker. Verifies LONG vs SHORT MAE/MFE math, self-
 * scheduled tick from LiveInstrumentCache, orphan cleanup against TradeRepository,
 * and the explicit-tick API.
 */
class MaeMfeTrackerTest {

    private static final Instant ENTRY_AT = Instant.parse("2026-06-01T09:30:00Z");

    private TradeRepository tradeRepo;
    private LiveInstrumentCache liveCache;
    private MaeMfeTracker tracker;

    @BeforeEach
    void setUp() {
        tradeRepo = Mockito.mock(TradeRepository.class);
        liveCache = Mockito.mock(LiveInstrumentCache.class);
        when(tradeRepo.findByStatus(any(TradeStatus.class))).thenReturn(List.of());
        tracker = new MaeMfeTracker(tradeRepo, liveCache);
    }

    @Test
    void longTrade_tracksMaeAndMfeFromExplicitTicks() {
        tracker.onEntry(longEntry("TRD-1", new BigDecimal("100.00")));

        // Premium goes up to 120, then down to 92, then back up to 110.
        tracker.onTick("TRD-1", new BigDecimal("110.00"), 23_500, ENTRY_AT.plusSeconds(60));
        tracker.onTick("TRD-1", new BigDecimal("120.00"), 23_510, ENTRY_AT.plusSeconds(120));
        tracker.onTick("TRD-1", new BigDecimal("92.00"),  23_490, ENTRY_AT.plusSeconds(240));
        tracker.onTick("TRD-1", new BigDecimal("110.00"), 23_505, ENTRY_AT.plusSeconds(360));

        Snapshot snap = tracker.onExit("TRD-1").get();
        assertThat(snap.mfePct()).isEqualTo(20.0);                       // 120/100 → +20%
        assertThat(snap.mfeAt()).isEqualTo(ENTRY_AT.plusSeconds(120));
        assertThat(snap.spotAtMfe()).isEqualTo(23_510);
        assertThat(snap.maePct()).isEqualTo(-8.0);                       // 92/100 → -8%
        assertThat(snap.maeAt()).isEqualTo(ENTRY_AT.plusSeconds(240));
        assertThat(snap.spotAtMae()).isEqualTo(23_490);
        assertThat(snap.timeToMfeSec()).isEqualTo(120);
        assertThat(snap.timeToMaeSec()).isEqualTo(240);
        assertThat(snap.tickCount()).isEqualTo(4);
    }

    @Test
    void shortTrade_invertsSignal_butSameMinMaxSemantics() {
        tracker.onEntry(shortEntry("TRD-2", new BigDecimal("100.00")));

        // Premium goes down to 80 (profit on short), up to 120 (loss on short).
        tracker.onTick("TRD-2", new BigDecimal("80.00"),  23_500, ENTRY_AT.plusSeconds(60));
        tracker.onTick("TRD-2", new BigDecimal("120.00"), 23_510, ENTRY_AT.plusSeconds(120));

        Snapshot snap = tracker.onExit("TRD-2").get();
        // SHORT pnlPct = (entry - current) / entry × 100
        // current=80 → pnlPct = +20 (favorable on short)
        // current=120 → pnlPct = -20 (adverse on short)
        assertThat(snap.mfePct()).isEqualTo(20.0);
        assertThat(snap.mfeAt()).isEqualTo(ENTRY_AT.plusSeconds(60));
        assertThat(snap.maePct()).isEqualTo(-20.0);
        assertThat(snap.maeAt()).isEqualTo(ENTRY_AT.plusSeconds(120));
    }

    @Test
    void tickOnUnknownTrade_isNoop() {
        // No onEntry called.
        tracker.onTick("ghost", new BigDecimal("100"), 23_500, ENTRY_AT.plusSeconds(60));
        assertThat(tracker.activeTradeCount()).isZero();
        assertThat(tracker.peek("ghost")).isEmpty();
    }

    @Test
    void tickWithNonPositivePremium_isNoop() {
        tracker.onEntry(longEntry("TRD-1", new BigDecimal("100.00")));
        tracker.onTick("TRD-1", BigDecimal.ZERO, 23_500, ENTRY_AT.plusSeconds(60));
        tracker.onTick("TRD-1", new BigDecimal("-1"), 23_500, ENTRY_AT.plusSeconds(60));

        Snapshot snap = tracker.peek("TRD-1").get();
        assertThat(snap.tickCount()).isZero();
        assertThat(snap.maePct()).isZero();
        assertThat(snap.mfePct()).isZero();
    }

    @Test
    void onExitRemovesFromActiveSet() {
        tracker.onEntry(longEntry("TRD-1", new BigDecimal("100.00")));
        assertThat(tracker.activeTradeCount()).isEqualTo(1);

        Optional<Snapshot> first = tracker.onExit("TRD-1");
        assertThat(first).isPresent();
        assertThat(tracker.activeTradeCount()).isZero();
        // Second onExit returns empty.
        assertThat(tracker.onExit("TRD-1")).isEmpty();
    }

    @Test
    void scheduledTick_pullsCurrentLtpFromLiveInstrumentCache() {
        tracker.onEntry(longEntry("TRD-1", new BigDecimal("100.00")));

        // Buyer-side: spot moves up to 23_520, option LTP up to 140 → mfePct should be +40.
        when(liveCache.getFuturesPrice(IndexType.NIFTY)).thenReturn(23_520.0);
        OptionInstrument opt = mockOption(23_500, OptionType.CE, 140.0);
        when(liveCache.getBySymbol("NFO:NIFTY25JUN23500CE")).thenReturn(Optional.of(opt));

        tracker.scheduledTick();

        Snapshot snap = tracker.peek("TRD-1").get();
        assertThat(snap.mfePct()).isEqualTo(40.0);
        assertThat(snap.tickCount()).isEqualTo(1);
        assertThat(snap.lastPremium()).isEqualByComparingTo("140.0");
    }

    @Test
    void scheduledTick_skipsWhenSpotUnavailable() {
        tracker.onEntry(longEntry("TRD-1", new BigDecimal("100.00")));
        when(liveCache.getFuturesPrice(IndexType.NIFTY)).thenReturn(0.0);

        tracker.scheduledTick();
        assertThat(tracker.peek("TRD-1").get().tickCount()).isZero();
    }

    @Test
    void scheduledTick_skipsWhenOptionUnavailable() {
        tracker.onEntry(longEntry("TRD-1", new BigDecimal("100.00")));
        when(liveCache.getFuturesPrice(IndexType.NIFTY)).thenReturn(23_500.0);
        when(liveCache.getBySymbol("NFO:NIFTY25JUN23500CE")).thenReturn(Optional.empty());

        tracker.scheduledTick();
        assertThat(tracker.peek("TRD-1").get().tickCount()).isZero();
    }

    @Test
    void scheduledTick_emptyActiveSet_isNoop() {
        tracker.scheduledTick();        // no tracked trades — nothing to do
        Mockito.verifyNoInteractions(liveCache);
    }

    @Test
    void scheduledTick_perTradeFailureDoesNotAbortOthers() {
        tracker.onEntry(longEntry("TRD-A", new BigDecimal("100.00")));
        tracker.onEntry(longEntry("TRD-B", new BigDecimal("100.00")));

        when(liveCache.getFuturesPrice(IndexType.NIFTY))
                .thenThrow(new RuntimeException("network down"));

        tracker.scheduledTick();        // must not propagate

        // Both states preserved; no ticks applied.
        assertThat(tracker.activeTradeCount()).isEqualTo(2);
    }

    @Test
    void scheduledCleanup_dropsOrphanedTrades() {
        tracker.onEntry(longEntry("TRD-OPEN", new BigDecimal("100.00")));
        tracker.onEntry(longEntry("TRD-CLOSED", new BigDecimal("100.00")));

        TradeEntity stillOpen = Mockito.mock(TradeEntity.class);
        when(stillOpen.getTradeId()).thenReturn("TRD-OPEN");
        when(tradeRepo.findByStatus(TradeStatus.OPEN)).thenReturn(List.of(stillOpen));

        tracker.scheduledCleanup();

        assertThat(tracker.peek("TRD-OPEN")).isPresent();
        assertThat(tracker.peek("TRD-CLOSED")).isEmpty();
        assertThat(tracker.activeTradeCount()).isEqualTo(1);
    }

    @Test
    void scheduledCleanup_emptyActiveSet_isNoop() {
        tracker.scheduledCleanup();
        Mockito.verifyNoInteractions(tradeRepo);
    }

    @Test
    void entryContextRejectsInvalidPremium() {
        assertThatThrownBy(() -> longEntry("TRD-X", BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> longEntry("TRD-X", new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void peekReturnsSnapshotWithoutRemovingState() {
        tracker.onEntry(longEntry("TRD-1", new BigDecimal("100.00")));
        tracker.onTick("TRD-1", new BigDecimal("110.00"), 23_500, ENTRY_AT.plusSeconds(60));

        Snapshot first = tracker.peek("TRD-1").get();
        Snapshot second = tracker.peek("TRD-1").get();

        assertThat(first.tickCount()).isEqualTo(1);
        assertThat(second.tickCount()).isEqualTo(1);
        assertThat(tracker.activeTradeCount()).isEqualTo(1);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static EntryContext longEntry(String tradeId, BigDecimal entryPremium) {
        return new EntryContext(tradeId, StrategyType.OI_MOMENTUM, IndexType.NIFTY,
                "decision-1", Direction.LONG, "NFO:NIFTY25JUN23500CE",
                23_500, OptionType.CE, entryPremium, 23_500.0, ENTRY_AT);
    }

    private static EntryContext shortEntry(String tradeId, BigDecimal entryPremium) {
        return new EntryContext(tradeId, StrategyType.SHORT_STRADDLE, IndexType.NIFTY,
                "decision-2", Direction.SHORT, "NFO:NIFTY25JUN23500CE",
                23_500, OptionType.CE, entryPremium, 23_500.0, ENTRY_AT);
    }

    private static OptionInstrument mockOption(int strike, OptionType type, double ltp) {
        OptionInstrument o = Mockito.mock(OptionInstrument.class);
        when(o.getStrikePrice()).thenReturn(strike);
        when(o.getOptionType()).thenReturn(type.name());
        when(o.getLastPrice()).thenReturn(ltp);
        return o;
    }
}
