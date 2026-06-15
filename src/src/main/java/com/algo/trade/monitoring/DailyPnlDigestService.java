package com.algo.trade.monitoring;

import com.algo.trade.domain.PositionGroupStatus;
import com.algo.trade.domain.TradeStatus;
import com.algo.trade.notification.TelegramAlertService;
import com.algo.trade.persistence.PositionGroupEntity;
import com.algo.trade.persistence.PositionGroupRepository;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.persistence.TradeRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Sends a daily P&L digest via Telegram at 15:35 IST (after market close).
 * Summarizes single-leg trades + spread positions closed today.
 */
@Service
public class DailyPnlDigestService {

    private static final Logger log = LoggerFactory.getLogger(DailyPnlDigestService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final TradeRepository tradeRepository;
    private final PositionGroupRepository positionGroupRepository;
    private final TelegramAlertService telegramAlertService;

    public DailyPnlDigestService(TradeRepository tradeRepository,
                                  PositionGroupRepository positionGroupRepository,
                                  TelegramAlertService telegramAlertService) {
        this.tradeRepository = tradeRepository;
        this.positionGroupRepository = positionGroupRepository;
        this.telegramAlertService = telegramAlertService;
    }

    @Scheduled(cron = "0 35 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void sendDailyDigest() {
        try {
            String digest = buildDigest();
            telegramAlertService.systemAlert(digest);
            log.info("[DailyDigest] Sent daily P&L digest");
        } catch (Exception e) {
            log.warn("[DailyDigest] Failed to send digest: {}", e.getMessage());
        }
    }

    private String buildDigest() {
        Instant todayStart = LocalDate.now(IST).atStartOfDay(IST).toInstant();
        Instant now = Instant.now();

        // Single-leg trades
        List<TradeEntity> todayTrades = tradeRepository.findByEntryTimeBetween(todayStart, now);
        long singleLegOpen = todayTrades.stream()
                .filter(t -> t.getStatus() == TradeStatus.OPEN && !t.isPaperTrade()).count();
        long singleLegClosed = todayTrades.stream()
                .filter(t -> t.getStatus() == TradeStatus.CLOSED && !t.isPaperTrade()).count();
        BigDecimal singleLegPnl = todayTrades.stream()
                .filter(t -> t.getStatus() == TradeStatus.CLOSED && !t.isPaperTrade())
                .map(TradeEntity::getRealizedPnl)
                .filter(p -> p != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Spread positions
        List<PositionGroupEntity> allGroups = positionGroupRepository.findByStatus(PositionGroupStatus.CLOSED);
        List<PositionGroupEntity> todayClosed = allGroups.stream()
                .filter(g -> g.getExitTime() != null && g.getExitTime().isAfter(todayStart))
                .toList();
        long spreadsClosed = todayClosed.size();
        BigDecimal spreadPnl = todayClosed.stream()
                .map(PositionGroupEntity::getPnl)
                .filter(p -> p != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long spreadsOpen = positionGroupRepository.findByOpenTrue().stream()
                .filter(g -> g.getStatus() == PositionGroupStatus.OPEN).count();

        BigDecimal totalPnl = singleLegPnl.add(spreadPnl);
        String emoji = totalPnl.signum() >= 0 ? "📈" : "📉";

        return String.format(
                "%s Daily P&L Digest — %s\n\n"
                + "Single-leg: %d closed, %d open\n"
                + "Single-leg P&L: ₹%s\n\n"
                + "Spreads: %d closed, %d open\n"
                + "Spread P&L: ₹%s\n\n"
                + "━━━━━━━━━━━━━━━━━━━━\n"
                + "Total P&L: ₹%s %s",
                emoji, LocalDate.now(IST),
                singleLegClosed, singleLegOpen,
                singleLegPnl.setScale(0, RoundingMode.HALF_UP),
                spreadsClosed, spreadsOpen,
                spreadPnl.setScale(0, RoundingMode.HALF_UP),
                totalPnl.setScale(0, RoundingMode.HALF_UP),
                totalPnl.signum() >= 0 ? "✅" : "❌"
        );
    }
}
