package com.algo.trade.tuning.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.algo.trade.config.SpreadTradingProperties;
import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.OrderSide;
import com.algo.trade.domain.SpreadLeg;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.LegEvent;
import com.algo.trade.tuning.TuningEventType;
import com.algo.trade.tuning.capture.CaptureSettings;
import com.algo.trade.tuning.capture.CaptureToggleService;
import com.algo.trade.tuning.recorder.IstDayClock;
import com.algo.trade.tuning.recorder.TuningEventCsvWriter;
import com.algo.trade.tuning.recorder.TuningEventRecorder;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

class LegEventEmitterTest {

    @TempDir
    Path tempDir;

    private TuningEventRecorder recorder;
    private LegEventEmitter emitter;

    @BeforeEach
    void setUp() {
        CaptureToggleService toggle = Mockito.mock(CaptureToggleService.class);
        when(toggle.isEnabled(Mockito.any(), Mockito.eq(TuningEventType.LEG)))
                .thenReturn(true);
        when(toggle.settingsFor(Mockito.any()))
                .thenReturn(new CaptureSettings(
                        StrategyType.BULL_CALL_SPREAD, true, true, true, true, true, true, true, 60, null));
        Clock clock = Clock.fixed(Instant.parse("2026-06-01T10:00:00Z"), ZoneId.of("Asia/Kolkata"));
        recorder = new TuningEventRecorder(tempDir, toggle, new TuningEventCsvWriter(), new IstDayClock(clock));
        SpreadTradingProperties props = SpreadTradingProperties.defaults();
        emitter = new LegEventEmitter(recorder, props);
    }

    @Test
    void emitLeg_writesLegCsvRow() throws Exception {
        SpreadLeg leg = new SpreadLeg("NIFTY2560623500CE", 23500, OptionType.CE, OrderSide.BUY, 50,
                LocalDate.of(2026, 6, 26));
        emitter.emitLeg(StrategyType.BULL_CALL_SPREAD, UnderlyingSymbol.NIFTY, "grp-1", 1, leg,
                "FILLED", 50, 50, BigDecimal.valueOf(99.5));

        try (var paths = Files.walk(tempDir)) {
            List<Path> legFiles = paths.filter(p -> p.getFileName().toString().equals("leg.csv")).toList();
            assertThat(legFiles).isNotEmpty();
            String content = Files.readString(legFiles.getFirst());
            assertThat(content).contains("legNumber", "FILLED", "NIFTY2560623500CE");
        }
    }
}
