package com.algo.trade.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algo.trade.notification.TelegramAlertService;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SignalTuningReportServiceTest {

    @Mock
    private TelegramAlertService telegramAlertService;

    @Mock
    private TuningReportService tuningReportService;

    @Test
    void generateSubmitsJobAndNotifiesTelegram() {
        when(tuningReportService.submit(any(), any(), anySet(), anyString(), anyBoolean(), any()))
                .thenReturn("JOB-TEST123");

        SignalTuningReportService service =
                new SignalTuningReportService(telegramAlertService, tuningReportService);
        SignalTuningReportService.TuningRunResult result = service.generate();

        assertThat(result.htmlReportPath()).contains("JOB-TEST123");
        verify(telegramAlertService).signalTuningReport(org.mockito.ArgumentMatchers.contains("JOB-TEST123"));
        verify(tuningReportService).submit(
                any(LocalDate.class), any(LocalDate.class), anySet(), anyString(), anyBoolean(), any());
    }
}
