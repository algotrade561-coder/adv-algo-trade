package com.algo.trade.controller;

import com.algo.trade.persistence.OrderEntity;
import com.algo.trade.persistence.OrderRepository;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.persistence.TradeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Audit Export Controller — exports trade data as CSV for SEBI compliance.
 *
 * Provides downloadable CSV of all trades within a date range, including:
 * - Entry/exit time, symbol, side, quantity, price, P&L
 * - Strategy name, order IDs
 * - Suitable for regulatory audit trail
 */
@RestController
@RequestMapping("/audit")
public class AuditExportController {

    private static final Logger log = LoggerFactory.getLogger(AuditExportController.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TradeRepository tradeRepository;
    private final OrderRepository orderRepository;

    public AuditExportController(TradeRepository tradeRepository, OrderRepository orderRepository) {
        this.tradeRepository = tradeRepository;
        this.orderRepository = orderRepository;
    }

    /**
     * Export all trades as CSV.
     * GET /audit/trades/csv?from=2026-01-01&to=2026-06-01
     */
    @GetMapping("/trades/csv")
    public ResponseEntity<byte[]> exportTradesCsv(
            @RequestParam(defaultValue = "") String from,
            @RequestParam(defaultValue = "") String to) {

        Instant fromInstant = from.isBlank()
                ? LocalDate.now(IST).minusDays(30).atStartOfDay(IST).toInstant()
                : LocalDate.parse(from).atStartOfDay(IST).toInstant();
        Instant toInstant = to.isBlank()
                ? Instant.now()
                : LocalDate.parse(to).plusDays(1).atStartOfDay(IST).toInstant();

        List<TradeEntity> trades = tradeRepository.findAll().stream()
                .filter(t -> t.getEntryTime() != null)
                .filter(t -> t.getEntryTime().isAfter(fromInstant) && t.getEntryTime().isBefore(toInstant))
                .toList();

        StringBuilder csv = new StringBuilder();
        csv.append("EntryTime,ExitTime,Symbol,StrategyType,OptionType,Quantity,EntryPrice,ExitPrice,RealizedPnl,ExitReason\n");

        for (TradeEntity t : trades) {
            csv.append(formatInstant(t.getEntryTime())).append(",");
            csv.append(formatInstant(t.getExitTime())).append(",");
            csv.append(safe(t.getInstrumentKey())).append(",");
            csv.append(safe(t.getStrategyType())).append(",");
            csv.append(safe(t.getOptionType())).append(",");
            csv.append(t.getQuantity()).append(",");
            csv.append(t.getEntryPrice() != null ? t.getEntryPrice() : "").append(",");
            csv.append(t.getExitPrice() != null ? t.getExitPrice() : "").append(",");
            csv.append(t.getRealizedPnl() != null ? t.getRealizedPnl() : "").append(",");
            csv.append(safe(t.getExitReason())).append("\n");
        }

        String filename = "trades_audit_" + LocalDate.now(IST).format(DATE_FMT) + ".csv";
        log.info("[Audit] Exported {} trades to CSV", trades.size());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv.toString().getBytes());
    }

    /**
     * Export all orders as CSV.
     * GET /audit/orders/csv?from=2026-01-01&to=2026-06-01
     */
    @GetMapping("/orders/csv")
    public ResponseEntity<byte[]> exportOrdersCsv(
            @RequestParam(defaultValue = "") String from,
            @RequestParam(defaultValue = "") String to) {

        Instant fromInstant = from.isBlank()
                ? LocalDate.now(IST).minusDays(30).atStartOfDay(IST).toInstant()
                : LocalDate.parse(from).atStartOfDay(IST).toInstant();
        Instant toInstant = to.isBlank()
                ? Instant.now()
                : LocalDate.parse(to).plusDays(1).atStartOfDay(IST).toInstant();

        List<OrderEntity> orders = orderRepository.findAll().stream()
                .filter(o -> o.getOrderPlacedAt() != null)
                .filter(o -> o.getOrderPlacedAt().isAfter(fromInstant) && o.getOrderPlacedAt().isBefore(toInstant))
                .toList();

        StringBuilder csv = new StringBuilder();
        csv.append("PlacedAt,BrokerOrderId,Symbol,Side,Quantity,Price,Status,StrategyType\n");

        for (OrderEntity o : orders) {
            csv.append(formatInstant(o.getOrderPlacedAt())).append(",");
            csv.append(safe(o.getBrokerOrderId())).append(",");
            csv.append(safe(o.getInstrumentKey())).append(",");
            csv.append(safe(o.getSide())).append(",");
            csv.append(o.getRequestedQuantity()).append(",");
            csv.append(o.getAverageFillPrice() != null ? o.getAverageFillPrice() : "").append(",");
            csv.append(o.getStatus() != null ? o.getStatus().name() : "").append(",");
            csv.append(safe(o.getStrategyType())).append("\n");
        }

        String filename = "orders_audit_" + LocalDate.now(IST).format(DATE_FMT) + ".csv";
        log.info("[Audit] Exported {} orders to CSV", orders.size());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv.toString().getBytes());
    }

    private String formatInstant(Instant instant) {
        if (instant == null) return "";
        return instant.atZone(IST).format(DATETIME_FMT);
    }

    private String safe(String value) {
        return value != null ? value.replace(",", ";") : "";
    }
}
