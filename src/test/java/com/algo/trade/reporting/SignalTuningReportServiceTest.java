package com.algo.trade.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.algo.trade.notification.TelegramAlertService;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SignalTuningReportServiceTest {

    @Mock
    private TelegramAlertService telegramAlertService;

    @Test
    void formatTelegramSummaryIncludesTitleAndMetrics() {
        SignalTuningAnalyzer.Report report = new SignalTuningAnalyzer.Report(
                Instant.parse("2026-05-15T09:15:00+05:30"),
                Instant.parse("2026-05-15T15:30:00+05:30"),
                100,
                2,
                98,
                1,
                0,
                0,
                2,
                1,
                0,
                0,
                List.of(),
                List.of(),
                Map.of("BROKER_ERROR", 1L),
                List.of(new SignalTuningAnalyzer.Recommendation(
                        SignalTuningAnalyzer.Severity.CRITICAL,
                        "broker",
                        "1 BROKER_ERROR on entry",
                        "Fix IP whitelist")),
                OiMomentumTuningAnalyzer.OiReport.empty(),
                OiShiftTrapTuningAnalyzer.TrapReport.empty());

        String summary = SignalTuningReportService.formatTelegramSummary(
                report, Path.of("reports/tuning/signal-tuning-test.html"));

        assertThat(summary).contains("Signal Tuning Report");
        assertThat(summary).contains("Evaluations: 100");
        assertThat(summary).contains("BROKER_ERROR");
        assertThat(summary).contains("DIRECTIONAL BUY w/o confirmation");
        assertThat(summary).contains("signal-tuning-test.html");
    }

    @Test
    void generateSendsTelegramWhenDataPresent() {
        SignalTuningProperties properties = new SignalTuningProperties();
        properties.setBlockDuringMarketHours(false);
        properties.setLoadForwardCandles(false);
        SignalTuningReportService service = new SignalTuningReportService(telegramAlertService, properties);
        if (!Path.of("reports/entry-signals/entry-signals.csv").toFile().exists()) {
            return;
        }
        service.generate();
        verify(telegramAlertService).signalTuningReport(org.mockito.ArgumentMatchers.contains("Signal Tuning Report"));
    }
}
