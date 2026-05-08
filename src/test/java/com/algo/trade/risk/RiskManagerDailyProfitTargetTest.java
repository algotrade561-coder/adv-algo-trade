package com.algo.trade.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.algo.trade.config.GlobalConfigService;
import com.algo.trade.config.TradingProperties;
import com.algo.trade.domain.TradeStatus;
import com.algo.trade.notification.TelegramAlertService;
import com.algo.trade.persistence.DailySummaryRepository;
import com.algo.trade.persistence.StrategyDecisionRepository;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.persistence.TradeRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RiskManagerDailyProfitTargetTest {

    private final TradeRepository tradeRepository = mock(TradeRepository.class);
    private final TelegramAlertService alertService = mock(TelegramAlertService.class);
    private final DailySummaryRepository dailySummaryRepository = mock(DailySummaryRepository.class);
    private final StrategyDecisionRepository decisionRepository = mock(StrategyDecisionRepository.class);

    private RiskManager createRiskManager(BigDecimal dailyProfitTarget) {
        TradingProperties props = new TradingProperties(null, false, null, null,
                null, null, null, null,
                new TradingProperties.Risk(
                        BigDecimal.valueOf(300_000), BigDecimal.valueOf(1.2), BigDecimal.valueOf(3),
                        6, 6, 2, 3, BigDecimal.TEN, 10, dailyProfitTarget),
                null, null, null, null);
        GlobalConfigService globalConfigService = mock(GlobalConfigService.class);
        when(globalConfigService.getTotalCapital()).thenReturn(BigDecimal.valueOf(300_000));
        when(globalConfigService.getMaxRiskPerTradePercent()).thenReturn(BigDecimal.valueOf(1.2));
        when(globalConfigService.getMaxDailyLossPercent()).thenReturn(BigDecimal.valueOf(3));
        when(globalConfigService.getMaxTradesPerDay()).thenReturn(6);
        when(globalConfigService.getMaxConsecutiveLosses()).thenReturn(2);
        when(globalConfigService.getMaxOpenTrades()).thenReturn(3);
        when(globalConfigService.getDailyProfitTarget()).thenReturn(dailyProfitTarget);
        return new RiskManager(globalConfigService, props, tradeRepository, alertService, dailySummaryRepository, decisionRepository);
    }

    private TradeEntity tradeWithPnl(BigDecimal pnl) {
        TradeEntity trade = new TradeEntity("t1", "NFO:NIFTY", "NIFTY", "CE",
                TradeStatus.CLOSED, 75, BigDecimal.valueOf(100), Instant.now(), "test");
        trade.close(BigDecimal.valueOf(120), Instant.now(), pnl, "target");
        return trade;
    }

    @Test
    void entryBlockedWhenDailyPnlExceedsProfitTarget() {
        RiskManager rm = createRiskManager(BigDecimal.valueOf(5000));
        when(tradeRepository.findByEntryTimeBetween(any(), any()))
                .thenReturn(List.of(tradeWithPnl(BigDecimal.valueOf(6000))));

        RiskManager.RiskCheckResult result = rm.validateEntry("NIFTY", 75);

        assertThat(result.isApproved()).isFalse();
        assertThat(result.reason()).isEqualTo("daily profit target reached");
    }

    @Test
    void entryAllowedWhenProfitTargetIsZeroDisabled() {
        RiskManager rm = createRiskManager(BigDecimal.ZERO);
        when(tradeRepository.findByEntryTimeBetween(any(), any()))
                .thenReturn(List.of(tradeWithPnl(BigDecimal.valueOf(10000))));

        RiskManager.RiskCheckResult result = rm.validateEntry("NIFTY", 75);

        assertThat(result.isApproved()).isTrue();
    }

    @Test
    void entryAllowedWhenPnlBelowProfitTarget() {
        RiskManager rm = createRiskManager(BigDecimal.valueOf(5000));
        when(tradeRepository.findByEntryTimeBetween(any(), any()))
                .thenReturn(List.of(tradeWithPnl(BigDecimal.valueOf(3000))));

        RiskManager.RiskCheckResult result = rm.validateEntry("NIFTY", 75);

        assertThat(result.isApproved()).isTrue();
    }

    @Test
    void entryBlockedWhenPnlExactlyEqualsToProfitTarget() {
        RiskManager rm = createRiskManager(BigDecimal.valueOf(5000));
        when(tradeRepository.findByEntryTimeBetween(any(), any()))
                .thenReturn(List.of(tradeWithPnl(BigDecimal.valueOf(5000))));

        RiskManager.RiskCheckResult result = rm.validateEntry("NIFTY", 75);

        assertThat(result.isApproved()).isFalse();
        assertThat(result.reason()).isEqualTo("daily profit target reached");
    }
}
