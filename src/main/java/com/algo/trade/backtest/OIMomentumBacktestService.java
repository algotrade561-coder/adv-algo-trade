package com.algo.trade.backtest;

import com.algo.trade.strategy.oimomentum.OIMomentumConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Backtests the OI Momentum strategy against Global Datafeeds 1-minute option CSV data.
 *
 * Uses the live {@link OIMomentumConfig} for all parameters — meaning every backtest
 * run automatically reflects the latest tuned values (SL, trail activation, trail gap,
 * multi-timeframe toggle, bias threshold, etc.).
 *
 * Input:  data/backtest/imports/global-datafeeds/by-day/YYYY/YYYY-MM-DD.csv
 * Output: {@link OIMomentumBacktestResult} with per-trade records + HTML report path.
 *
 * Strategy logic mirrors OIMomentumStrategy.detectEntry() / managePosition() exactly.
 */
@Service
public class OIMomentumBacktestService {

    private static final Logger log = LoggerFactory.getLogger(OIMomentumBacktestService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    // Timestamps in the by-day CSVs are ISO-8601 UTC (e.g. "2026-01-28T03:45:00Z").
    // Instant.parse() handles this natively — no custom formatter needed.
    private static final Pattern STRIKE_OPT_PATTERN = Pattern.compile("(\\d{4,5})(CE|PE)(?:\\.|$)");

    private final OIMomentumConfig config;

    public OIMomentumBacktestService(OIMomentumConfig config) {
        this.config = config;
    }

    // ── Public API ──────────────────────────────────────────────────────────────

    public record BacktestRequest(
            LocalDate from,
            LocalDate to,
            String underlying,          // "NIFTY", "BANKNIFTY", etc. — filters CSV instrument keys
            Path byDayDir               // path to global-datafeeds/by-day/
    ) {}

    public record OIMomentumBacktestResult(
            LocalDate from,
            LocalDate to,
            String underlying,
            int totalDays,
            int activeDays,
            List<OIMomentumBacktestTrade> trades,
            Map<String, Object> metrics,
            String htmlReport           // full HTML string
    ) {}

    /**
     * Run the backtest over the given date range.
     */
    public OIMomentumBacktestResult run(BacktestRequest request) {
        log.info("[OIMomentumBacktest] Starting: underlying={}, from={}, to={}",
                request.underlying(), request.from(), request.to());

        List<Path> dayFiles = collectDayFiles(request.byDayDir(), request.from(), request.to());
        log.info("[OIMomentumBacktest] Found {} day files", dayFiles.size());

        List<OIMomentumBacktestTrade> allTrades = new ArrayList<>();
        List<DaySummary> daySummaries = new ArrayList<>();

        for (Path f : dayFiles) {
            String dateStr = f.getFileName().toString().replace(".csv", "");
            try {
                DayData dayData = loadDay(f, request.underlying());
                if (dayData.isEmpty()) {
                    daySummaries.add(new DaySummary(dateStr, 0, 0, 0, 0.0));
                    continue;
                }
                List<OIMomentumBacktestTrade> dayTrades = simulateDay(dateStr, dayData);
                allTrades.addAll(dayTrades);
                double dayPnl = dayTrades.stream().mapToDouble(OIMomentumBacktestTrade::pnlInr).sum();
                int wins = (int) dayTrades.stream().filter(OIMomentumBacktestTrade::win).count();
                daySummaries.add(new DaySummary(dateStr, dayTrades.size(), wins, dayTrades.size() - wins, dayPnl));
            } catch (Exception e) {
                log.warn("[OIMomentumBacktest] Failed on {}: {}", dateStr, e.getMessage());
                daySummaries.add(new DaySummary(dateStr, 0, 0, 0, 0.0));
            }
        }

        Map<String, Object> metrics = computeMetrics(allTrades, daySummaries);
        String html = buildHtml(request, allTrades, daySummaries, metrics);

        int activeDays = (int) daySummaries.stream().filter(d -> d.trades() > 0).count();
        log.info("[OIMomentumBacktest] Done: {} trades on {} active days, net P&L={}",
                allTrades.size(), activeDays, metrics.get("netPnlInr"));

        return new OIMomentumBacktestResult(
                request.from(), request.to(), request.underlying(),
                daySummaries.size(), activeDays, allTrades, metrics, html);
    }

    // ── Day loading ─────────────────────────────────────────────────────────────

    /**
     * Minimal bar record: low + close + OI for SL/trail/exit tracking.
     */
    private record MinuteBar(double open, double high, double low, double close, long oi) {}
    private record StrikeKey(int strike, String optType) {}

    /**
     * Ordered map of IST LocalDateTime → (StrikeKey → MinuteBar).
     * Using LinkedHashMap to preserve timestamp order after sort.
     */
    private record DayData(
            // Sorted list of all timestamps in IST
            List<LocalDateTime> timestamps,
            // timestamp → strike key → bar
            Map<LocalDateTime, Map<StrikeKey, MinuteBar>> bars
    ) {
        boolean isEmpty() { return timestamps.isEmpty(); }
    }

    private DayData loadDay(Path csvFile, String underlying) throws IOException {
        Map<LocalDateTime, Map<StrikeKey, MinuteBar>> bars = new LinkedHashMap<>();
        String upperUnderlying = underlying.toUpperCase();

        try (BufferedReader br = Files.newBufferedReader(csvFile)) {
            String header = br.readLine();
            if (header == null) return new DayData(List.of(), Map.of());

            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] cols = line.split(",", -1);
                if (cols.length < 9) continue;

                String instrumentKey = cols[1].trim();
                // Filter by underlying (e.g. instrumentKey starts with "NIFTY" but not "BANKNIFTY")
                if (!matchesUnderlying(instrumentKey, upperUnderlying)) continue;

                Matcher m = STRIKE_OPT_PATTERN.matcher(instrumentKey);
                if (!m.find()) continue;
                int strike = Integer.parseInt(m.group(1));
                String optType = m.group(2);

                LocalDateTime ist = parseIst(cols[0].trim());
                if (ist == null) continue;

                double o  = parseDouble(cols[3]);
                double h  = parseDouble(cols[4]);
                double l  = parseDouble(cols[5]);
                double c  = parseDouble(cols[6]);
                long   oi = parseLong(cols[8]);

                bars.computeIfAbsent(ist, k -> new HashMap<>())
                    .put(new StrikeKey(strike, optType), new MinuteBar(o, h, l, c, oi));
            }
        }

        List<LocalDateTime> sorted = new ArrayList<>(bars.keySet());
        Collections.sort(sorted);
        return new DayData(sorted, bars);
    }

    private boolean matchesUnderlying(String key, String underlying) {
        String upper = key.toUpperCase();
        if (!upper.startsWith(underlying)) return false;
        // Avoid e.g. "NIFTY" matching "BANKNIFTY"
        if (underlying.equals("NIFTY") && upper.startsWith("BANKNIFTY")) return false;
        if (underlying.equals("NIFTY") && upper.startsWith("FINNIFTY")) return false;
        if (underlying.equals("NIFTY") && upper.startsWith("MIDCPNIFTY")) return false;
        return true;
    }

    // ── Simulation ──────────────────────────────────────────────────────────────

    private static final int STRIKE_INTERVAL = 50; // NIFTY default; good enough for all indices

    private List<OIMomentumBacktestTrade> simulateDay(String dateStr, DayData day) {
        List<OIMomentumBacktestTrade> trades = new ArrayList<>();

        // Per-day state
        ActivePosition pos = null;
        int tradeCount = 0;
        int consecutiveLosses = 0;
        LocalDateTime lastSlTime = null;
        LocalDateTime lastProfitTime = null;
        int lastProfitDir = 0;
        double dailyPnl = 0.0;

        // Rolling spot price history: (barIndex, spot)
        Deque<long[]> spotHistBits = new ArrayDeque<>(3000); // [barIdx, spotBits]
        Deque<double[]> spotHist = new ArrayDeque<>(3000);   // [barIdx, spot]

        // OI ring buffers per strike key
        Map<StrikeKey, Deque<long[]>> oiBuffers = new HashMap<>(); // [barIdx, oi]
        Map<StrikeKey, Long> lastOiChangeBars = new HashMap<>();

        // Confirmation tracking
        int confCount = 0;
        int confDir = 0;

        Double prevSpot = null;
        int barIdx = 0;

        for (LocalDateTime ts : day.timestamps()) {
            barIdx++;
            Map<StrikeKey, MinuteBar> snap = day.bars().get(ts);
            int h = ts.getHour(), mn = ts.getMinute();
            int hm = h * 60 + mn;

            // Only process market hours
            if (hm < 9 * 60 + 15 || hm > 15 * 60 + 30) continue;

            // Reconstruct spot
            double spot = reconstructSpot(snap, prevSpot);
            if (spot <= 0) continue;
            prevSpot = spot;
            int atm = (int) Math.round(spot / STRIKE_INTERVAL) * STRIKE_INTERVAL;

            final int finalBarIdx = barIdx;
            spotHist.addLast(new double[]{barIdx, spot});
            if (spotHist.size() > 3000) spotHist.pollFirst();

            // Update OI buffers (ATM ± 8 strikes)
            for (int off = -8; off <= 8; off++) {
                int s2 = atm + off * STRIKE_INTERVAL;
                for (String opt : new String[]{"CE", "PE"}) {
                    StrikeKey sk = new StrikeKey(s2, opt);
                    MinuteBar bar = snap.get(sk);
                    if (bar == null) continue;
                    Deque<long[]> buf = oiBuffers.computeIfAbsent(sk, k -> new ArrayDeque<>(100));
                    long prevOi = buf.isEmpty() ? -1 : buf.peekLast()[1];
                    buf.addLast(new long[]{barIdx, bar.oi()});
                    if (buf.size() > 100) buf.pollFirst();
                    if (prevOi >= 0 && bar.oi() != prevOi) {
                        lastOiChangeBars.put(sk, (long) barIdx);
                    }
                }
            }

            // ── Manage open position ──────────────────────────────────────────
            if (pos != null) {
                StrikeKey posKey = new StrikeKey(pos.strike, pos.optType);
                MinuteBar cur = snap.get(posKey);
                if (cur == null) {
                    // Try adjacent strikes
                    for (int adj : new int[]{pos.strike + STRIKE_INTERVAL, pos.strike - STRIKE_INTERVAL}) {
                        cur = snap.get(new StrikeKey(adj, pos.optType));
                        if (cur != null) break;
                    }
                }
                if (cur == null) {
                    if (hm >= 15 * 60 + 10) {
                        OIMomentumBacktestTrade t = closePos(pos, pos.entryPrice * 0.88, ts, "SQUAREOFF_NO_QUOTE", dateStr);
                        trades.add(t); dailyPnl += t.pnlInr();
                        consecutiveLosses = t.win() ? 0 : consecutiveLosses + 1;
                        pos = null;
                    }
                    continue;
                }

                pos.updatePeak(cur.close());
                double profitPct = (cur.close() - pos.entryPrice) / pos.entryPrice * 100;
                double barLowPct = (cur.low() - pos.entryPrice) / pos.entryPrice * 100;
                double peakPct = (pos.peakPrice - pos.entryPrice) / pos.entryPrice * 100;

                // Squareoff time
                if (hm >= config.getSquareoffHour() * 60 + config.getSquareoffMinute()) {
                    OIMomentumBacktestTrade t = closePos(pos, cur.close(), ts, "SQUAREOFF", dateStr);
                    trades.add(t); dailyPnl += t.pnlInr();
                    consecutiveLosses = t.win() ? 0 : consecutiveLosses + 1;
                    if (t.win()) { lastProfitTime = ts; lastProfitDir = pos.direction; }
                    pos = null; continue;
                }

                // Break-even stop
                double slPct = config.getStopLossPercent();
                double beTrigger = config.getBreakEvenTriggerPercent();
                if (beTrigger > 0 && peakPct >= beTrigger) slPct = 0.0;

                // Stop loss (on bar LOW for intrabar precision)
                if (barLowPct <= -slPct) {
                    double exitPrice = pos.entryPrice * (1.0 - slPct / 100.0);
                    String reason = (beTrigger > 0 && peakPct >= beTrigger) ? "BREAK_EVEN_STOP" : "STOP_LOSS";
                    OIMomentumBacktestTrade t = closePos(pos, exitPrice, ts, reason, dateStr);
                    trades.add(t); dailyPnl += t.pnlInr();
                    if ("STOP_LOSS".equals(reason)) { consecutiveLosses++; lastSlTime = ts; }
                    pos = null; continue;
                }

                // Trailing stop (on bar close)
                double trailAct = config.getTrailingActivationPercent();
                double trailGap = config.getTrailingGapPercent();
                if (peakPct >= trailAct) {
                    double excess = peakPct - trailAct;
                    double effectiveGap = Math.max(trailGap - excess * 0.6, trailGap * 0.4);
                    double trailLevel = peakPct - effectiveGap;
                    if (profitPct < trailLevel) {
                        OIMomentumBacktestTrade t = closePos(pos, cur.close(), ts, "TRAILING_STOP", dateStr);
                        trades.add(t); dailyPnl += t.pnlInr();
                        consecutiveLosses = t.win() ? 0 : consecutiveLosses + 1;
                        if (t.win()) { lastProfitTime = ts; lastProfitDir = pos.direction; }
                        pos = null; continue;
                    }
                }
                continue;
            }

            // ── Entry gates ───────────────────────────────────────────────────
            if (hm < 9 * 60 + 25 || hm >= 14 * 60 + 55) continue;
            if (tradeCount >= config.getMaxTradesPerDay()) continue;
            if (consecutiveLosses >= config.getConsecutiveLossPause()) continue;
            if (lastSlTime != null && barIdx - barIndexOf(lastSlTime, day) < config.getCooldownAfterSlSeconds() / 60)
                continue;
            if (dailyPnl < -60_000) continue;
            // Midday throttle
            int middayThreshold = Math.max(1,
                    (int) Math.round(config.getSoftTargetTradesPerDay() * config.getMiddayTradeReductionPercent() / 100.0));
            if (hm > 12 * 60 && hm < 13 * 60 && tradeCount >= middayThreshold) continue;

            // ── OI delta (3-bar lookback) ──────────────────────────────────────
            int oiLookback = 3;
            long ceDelta = oiDelta(oiBuffers, new StrikeKey(atm, "CE"), barIdx, oiLookback);
            long peDelta = oiDelta(oiBuffers, new StrikeKey(atm, "PE"), barIdx, oiLookback);

            long atmCeLastChange = lastOiChangeBars.getOrDefault(new StrikeKey(atm, "CE"), 0L);
            long atmPeLastChange = lastOiChangeBars.getOrDefault(new StrikeKey(atm, "PE"), 0L);
            long latestOiChange = Math.max(atmCeLastChange, atmPeLastChange);
            boolean oiAvailable = latestOiChange > 0 && (barIdx - latestOiChange) <= 10;
            int oiDir = oiAvailable ? computeOiDir(ceDelta, peDelta) : 0;

            // ── PCR ────────────────────────────────────────────────────────────
            long totalCeOi = snap.entrySet().stream()
                    .filter(e -> "CE".equals(e.getKey().optType()))
                    .mapToLong(e -> e.getValue().oi()).sum();
            long totalPeOi = snap.entrySet().stream()
                    .filter(e -> "PE".equals(e.getKey().optType()))
                    .mapToLong(e -> e.getValue().oi()).sum();
            double pcr = totalCeOi > 0 ? (double) totalPeOi / totalCeOi : 0.0;
            int pcrDir = pcrDir(pcr);

            // ── 30M range ──────────────────────────────────────────────────────
            double[] range = computeRange(spotHist, barIdx, 30);
            double hi30 = range[0], lo30 = range[1];
            double rng30 = (lo30 > 0 && hi30 > 0) ? (hi30 - lo30) / lo30 * 100 : 0;

            // ── Momentum (30M primary; 15M+5M if multi-timeframe enabled) ─────
            MomentumResult momentum = detectMomentum(spotHist, spot, barIdx);
            if (momentum == null) { confCount = 0; confDir = 0; continue; }

            // ── Case matrix ────────────────────────────────────────────────────
            int md = momentum.direction();
            CaseResult caseResult = evalCase(md, oiDir, pcrDir, oiAvailable,
                    ceDelta, peDelta, rng30, momentum.type());
            if (caseResult.direction() == 0) { confCount = 0; confDir = 0; continue; }

            // ── Bias score ─────────────────────────────────────────────────────
            boolean openNoise = hm < 9 * 60 + 30;
            double bias = biasScore(md, oiDir, pcrDir, oiAvailable,
                    totalCeOi, totalPeOi, latestOiChange, barIdx, openNoise);
            if (bias < config.getBiasConfidenceThreshold()) { confCount = 0; confDir = 0; continue; }

            // ── Confirmation ticks ─────────────────────────────────────────────
            if (md == confDir) confCount++;
            else { confCount = 1; confDir = md; }
            boolean reEntryBoost = lastProfitTime != null
                    && lastProfitDir == md
                    && Duration.between(lastProfitTime, ts).getSeconds() < config.getReEntryBoostWindowSeconds();
            int required = reEntryBoost ? 1 : config.getBiasConfirmationTicks();
            if (confCount < required) continue;

            // ── Execute entry ──────────────────────────────────────────────────
            confCount = 0; confDir = 0;
            String optType = md > 0 ? "CE" : "PE";
            StrikeKey entryKey = new StrikeKey(atm, optType);
            MinuteBar optBar = snap.get(entryKey);
            int useStrike = atm;
            if (optBar == null) {
                StrikeKey adj1 = new StrikeKey(atm + STRIKE_INTERVAL, optType);
                StrikeKey adj2 = new StrikeKey(atm - STRIKE_INTERVAL, optType);
                if (snap.containsKey(adj1)) { optBar = snap.get(adj1); useStrike = atm + STRIKE_INTERVAL; }
                else if (snap.containsKey(adj2)) { optBar = snap.get(adj2); useStrike = atm - STRIKE_INTERVAL; }
            }
            if (optBar == null || optBar.close() <= 3) continue;

            double ep = optBar.close() * (1.0 + 0.20 / 100.0); // 0.2% slippage
            pos = new ActivePosition(useStrike, optType, ep, md, ts);
            tradeCount++;
        }

        return trades;
    }

    // ── Momentum detection ──────────────────────────────────────────────────────

    private record MomentumResult(int direction, String type, double magnitude) {}

    private MomentumResult detectMomentum(Deque<double[]> spotHist, double spot, int barIdx) {
        // Primary: 30M window
        MomentumResult r30 = detectInWindow(spotHist, spot, barIdx, 30,
                config.getMomentumThresholdPercent());
        if (r30 != null) return r30;

        // Secondary: 15M and 5M (only if multi-timeframe is enabled)
        if (config.isMultiTimeframeEnabled()) {
            MomentumResult r15 = detectInWindow(spotHist, spot, barIdx, 15,
                    config.getMomentumThresholdPercent());
            if (r15 != null) return r15;
            MomentumResult r5 = detectInWindow(spotHist, spot, barIdx, 5,
                    config.getShortTimeframeThresholdPct());
            if (r5 != null) return r5;
        }
        return null;
    }

    private MomentumResult detectInWindow(Deque<double[]> spotHist, double spot,
                                          int barIdx, int windowBars, double thresholdPct) {
        if (spotHist.size() < Math.max(15, windowBars + 5)) return null;
        int cutWin = barIdx - windowBars;
        int cutRec = barIdx - 2; // exclude last 2 bars (noise)
        double hi = 0, lo = Double.MAX_VALUE;
        int cnt = 0;
        for (double[] s : spotHist) {
            int bi = (int) s[0];
            if (bi < cutWin || bi > cutRec) continue;
            if (s[1] > hi) hi = s[1];
            if (s[1] < lo) lo = s[1];
            cnt++;
        }
        if (cnt < Math.max(5, windowBars / 3) || hi <= 0 || lo == Double.MAX_VALUE) return null;
        String lbl = windowBars < 30 ? windowBars + "M" : "30M";
        double breakoutMin = 0.02; // % minimum distance above/below H/L

        if (spot > hi) {
            double pct = (spot - hi) / hi * 100;
            if (pct > breakoutMin) return new MomentumResult(1, lbl + "_HIGH_BREAK", pct);
        }
        if (spot < lo) {
            double pct = (lo - spot) / lo * 100;
            if (pct > breakoutMin) return new MomentumResult(-1, lbl + "_LOW_BREAK", pct);
        }
        // Large move: 5-bar price change
        double[] base = null;
        for (double[] s : spotHist) {
            if ((int) s[0] <= barIdx - 5) base = s;
        }
        if (base != null && base[1] > 0) {
            double mv = (spot - base[1]) / base[1] * 100;
            if (Math.abs(mv) >= thresholdPct) {
                return new MomentumResult(mv > 0 ? 1 : -1, "LARGE_MOVE", Math.abs(mv));
            }
        }
        return null;
    }

    // ── OI / PCR helpers ────────────────────────────────────────────────────────

    private long oiDelta(Map<StrikeKey, Deque<long[]>> buffers, StrikeKey key, int barIdx, int lookback) {
        Deque<long[]> buf = buffers.get(key);
        if (buf == null || buf.size() < 2) return 0;
        long[] latest = buf.peekLast();
        if (latest == null) return 0;
        int lb = barIdx - lookback;
        long[] older = null;
        for (long[] entry : buf) {
            if (entry[0] <= lb) older = entry;
            else break;
        }
        return older != null ? latest[1] - older[1] : 0;
    }

    private int computeOiDir(long ceDelta, long peDelta) {
        long sig = (long) (200_000); // lower threshold for 1-min data
        if (ceDelta < 0 && peDelta > 0 && Math.abs(ceDelta) >= sig && Math.abs(ceDelta) > 2 * Math.abs(peDelta)) return 0;
        if (peDelta < 0 && ceDelta > 0 && Math.abs(peDelta) >= sig && Math.abs(peDelta) > 2 * Math.abs(ceDelta)) return 0;
        if (peDelta > ceDelta && peDelta > 0) return 1;
        if (ceDelta > peDelta && ceDelta > 0) return -1;
        if (peDelta < 0 && ceDelta < 0 && peDelta < ceDelta) return -1;
        if (peDelta < 0 && ceDelta < 0 && ceDelta < peDelta) return 1;
        return 0;
    }

    private int pcrDir(double pcr) {
        if (pcr >= config.getPcrBullishThreshold()) return 1;
        if (pcr <= config.getPcrBearishThreshold()) return -1;
        return 0;
    }

    // ── Case matrix ─────────────────────────────────────────────────────────────

    private record CaseResult(int direction, String caseName) {}

    private CaseResult evalCase(int md, int od, int pcd, boolean oiAv,
                                 long ceDelta, long peDelta, double rng, String mtype) {
        if (oiAv && od == md && pcd == md) return new CaseResult(md, "CASE1_ALL_ALIGN");
        if (!oiAv && pcd == md && pcd != 0) return new CaseResult(md, "CASE2_M+PCR");
        if (oiAv && od == md && pcd == 0) {
            boolean isBreakout = mtype != null && (mtype.contains("HIGH_BREAK") || mtype.contains("LOW_BREAK"));
            boolean isSqueeze = ceDelta < 0 && peDelta < 0
                    && Math.abs(ceDelta) >= config.getMinSqueezeOiDelta()
                    && Math.abs(peDelta) >= config.getMinSqueezeOiDelta();
            if (!isBreakout && !isSqueeze && rng > 0 && rng < 0.3) return new CaseResult(0, "CASE3_RANGE_GUARD");
            return new CaseResult(md, "CASE3_M+OI");
        }
        if (oiAv && od != 0 && od != md) return new CaseResult(0, "CASE4_CONFLICT");
        return new CaseResult(0, "CASE5_SKIP");
    }

    // ── Bias score ──────────────────────────────────────────────────────────────

    private double biasScore(int md, int od, int pcd, boolean oiAv,
                             long tce, long tpe, long lastOiChange, int barIdx, boolean openNoise) {
        double s = 30.0;
        if (oiAv) s += (od == md ? 25 : (od != 0 ? -10 : 0));
        s += (pcd == md ? 20 : (pcd != 0 ? -5 : 0));
        // BAL: CE/PE OI balance
        if (tce > 0 && tpe > 0) {
            double r = (double) tce / tpe;
            if (md > 0 && r < 0.77) s += 15;
            else if (md < 0 && r > 1.30) s += 15;
        }
        if (openNoise) s -= 20;
        if (lastOiChange == 0) s -= config.getBiasDecayPenalty();
        else if ((barIdx - lastOiChange) > 10) s -= config.getBiasDecayPenalty();
        return Math.max(0.0, s);
    }

    // ── Spot reconstruction ─────────────────────────────────────────────────────

    private double reconstructSpot(Map<StrikeKey, MinuteBar> snap, Double prevSpot) {
        List<Double> pts = new ArrayList<>();
        // Group strikes that have both CE and PE
        Map<Integer, double[]> cepe = new HashMap<>();
        for (Map.Entry<StrikeKey, MinuteBar> e : snap.entrySet()) {
            int strike = e.getKey().strike();
            String opt = e.getKey().optType();
            double c = e.getValue().close();
            if (c <= 1) continue;
            cepe.computeIfAbsent(strike, k -> new double[]{-1, -1});
            if ("CE".equals(opt)) cepe.get(strike)[0] = c;
            else                  cepe.get(strike)[1] = c;
        }
        for (Map.Entry<Integer, double[]> e : cepe.entrySet()) {
            if (e.getValue()[0] > 0 && e.getValue()[1] > 0) {
                pts.add(e.getKey() + e.getValue()[0] - e.getValue()[1]);
            }
        }
        // 2 pairs is sufficient for a reliable median — early files have sparse chains
        if (pts.size() < 2) return prevSpot != null ? prevSpot : 0;
        pts.sort(Double::compareTo);
        double med = pts.get(pts.size() / 2);
        if (prevSpot != null && Math.abs(med - prevSpot) > 500) return prevSpot;
        return med;
    }

    // ── Range helper ────────────────────────────────────────────────────────────

    private double[] computeRange(Deque<double[]> spotHist, int barIdx, int windowBars) {
        int cutWin = barIdx - windowBars;
        double hi = 0, lo = Double.MAX_VALUE;
        for (double[] s : spotHist) {
            int bi = (int) s[0];
            if (bi < cutWin) continue;
            if (s[1] > hi) hi = s[1];
            if (s[1] < lo) lo = s[1];
        }
        return new double[]{hi, lo == Double.MAX_VALUE ? 0 : lo};
    }

    // ── Position helpers ────────────────────────────────────────────────────────

    private static class ActivePosition {
        final int strike;
        final String optType;
        final double entryPrice;
        final int direction;
        final LocalDateTime entryTime;
        double peakPrice;

        ActivePosition(int strike, String optType, double ep, int dir, LocalDateTime entryTime) {
            this.strike = strike; this.optType = optType; this.entryPrice = ep;
            this.direction = dir; this.entryTime = entryTime; this.peakPrice = ep;
        }

        void updatePeak(double price) { if (price > peakPrice) peakPrice = price; }
    }

    private OIMomentumBacktestTrade closePos(ActivePosition pos, double exitPrice,
                                              LocalDateTime exitTime, String reason, String date) {
        double pnlPct = (exitPrice - pos.entryPrice) / pos.entryPrice * 100;
        double pnlInr = (exitPrice - pos.entryPrice) * 65; // lot size 65
        int holdMins = (int) Duration.between(pos.entryTime, exitTime).toMinutes();
        String side = pos.direction > 0 ? "BUY_CE" : "BUY_PE";
        return new OIMomentumBacktestTrade(
                date,
                pos.entryTime.toLocalTime().toString().substring(0, 5),
                exitTime.toLocalTime().toString().substring(0, 5),
                side, pos.strike,
                round2(pos.entryPrice), round2(exitPrice),
                round2(pnlPct), round2(pnlInr),
                reason, holdMins, pnlInr > 0
        );
    }

    // ── Utility ─────────────────────────────────────────────────────────────────

    private int barIndexOf(LocalDateTime t, DayData day) {
        int i = day.timestamps().indexOf(t);
        return i < 0 ? 0 : i + 1;
    }

    private List<Path> collectDayFiles(Path byDayDir, LocalDate from, LocalDate to) {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(byDayDir)) {
            log.warn("[OIMomentumBacktest] by-day directory not found: {}", byDayDir);
            return files;
        }
        try {
            LocalDate cur = from;
            while (!cur.isAfter(to)) {
                Path yearDir = byDayDir.resolve(String.valueOf(cur.getYear()));
                Path f = yearDir.resolve(cur + ".csv");
                if (Files.exists(f)) files.add(f);
                cur = cur.plusDays(1);
            }
        } catch (Exception e) {
            log.error("[OIMomentumBacktest] Error collecting files: {}", e.getMessage());
        }
        return files;
    }

    private LocalDateTime parseIst(String ts) {
        try {
            // By-day CSVs store timestamps in UTC with a real 'Z' suffix (ISO-8601).
            // e.g. "2026-01-28T03:45:00Z" = 03:45 UTC = 09:15 IST.
            // Instant.parse() handles the Z as UTC natively; then convert to IST.
            return Instant.parse(ts).atZone(IST).toLocalDateTime();
        } catch (Exception e) {
            return null;
        }
    }

    private double parseDouble(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0; }
    }

    private long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; }
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    // ── Metrics ─────────────────────────────────────────────────────────────────

    private record DaySummary(String date, int trades, int wins, int losses, double pnl) {}

    private Map<String, Object> computeMetrics(List<OIMomentumBacktestTrade> trades,
                                               List<DaySummary> days) {
        Map<String, Object> m = new LinkedHashMap<>();

        // Compute raw stats (safe even when trades list is empty)
        List<OIMomentumBacktestTrade> wins   = trades.stream().filter(OIMomentumBacktestTrade::win).toList();
        List<OIMomentumBacktestTrade> losses = trades.stream().filter(t -> !t.win()).toList();
        double netPnl    = trades.stream().mapToDouble(OIMomentumBacktestTrade::pnlInr).sum();
        double avgWin    = wins  .stream().mapToDouble(OIMomentumBacktestTrade::pnlInr).average().orElse(0);
        double avgLoss   = losses.stream().mapToDouble(OIMomentumBacktestTrade::pnlInr).average().orElse(0);
        double grossWin  = wins  .stream().mapToDouble(OIMomentumBacktestTrade::pnlInr).sum();
        double grossLoss = losses.stream().mapToDouble(OIMomentumBacktestTrade::pnlInr).sum();
        double pf        = grossLoss < 0 ? Math.abs(grossWin / grossLoss) : 0;
        int activeDays   = (int) days.stream().filter(d -> d.trades() > 0).count();

        // Max drawdown
        double peak = 0, mdd = 0, run = 0;
        for (DaySummary d : days) {
            run += d.pnl();
            if (run > peak) peak = run;
            if (peak - run > mdd) mdd = peak - run;
        }

        // Always populate every key so buildHtml never encounters a null value
        m.put("totalTrades",        trades.size());
        m.put("activeDays",         activeDays);
        m.put("tradesPerActiveDay", activeDays > 0 ? round2((double) trades.size() / activeDays) : 0.0);
        m.put("winCount",           wins.size());
        m.put("lossCount",          losses.size());
        m.put("winRate",            trades.isEmpty() ? 0.0 : round2((double) wins.size() / trades.size() * 100));
        m.put("netPnlInr",          round2(netPnl));
        m.put("avgWinInr",          round2(avgWin));
        m.put("avgLossInr",         round2(avgLoss));
        m.put("winLossRatio",       avgLoss != 0 ? round2(Math.abs(avgWin / avgLoss)) : 0.0);
        m.put("profitFactor",       round2(pf));
        m.put("maxDrawdownInr",     round2(mdd));
        m.put("grossWinInr",        round2(grossWin));
        m.put("grossLossInr",       round2(grossLoss));
        // Config snapshot
        m.put("config_slPercent",       config.getStopLossPercent());
        m.put("config_trailAct",        config.getTrailingActivationPercent());
        m.put("config_trailGap",        config.getTrailingGapPercent());
        m.put("config_biasThreshold",   config.getBiasConfidenceThreshold());
        m.put("config_multiTimeframe",  config.isMultiTimeframeEnabled());
        return m;
    }

    // ── HTML report ─────────────────────────────────────────────────────────────

    private String buildHtml(BacktestRequest req, List<OIMomentumBacktestTrade> trades,
                             List<DaySummary> days, Map<String, Object> m) {
        double net = ((Number) m.getOrDefault("netPnlInr", 0)).doubleValue();
        String netColor = net >= 0 ? "#27ae60" : "#e74c3c";

        StringBuilder dayRows = new StringBuilder();
        List<Double> cumPnl = new ArrayList<>();
        double run = 0;
        for (DaySummary d : days) {
            run += d.pnl();
            cumPnl.add(round2(run));
            String clr = d.pnl() > 0 ? "#27ae60" : (d.pnl() < 0 ? "#e74c3c" : "#7f8c8d");
            dayRows.append(String.format(
                "<tr><td>%s</td><td>%d</td><td>%d</td><td>%d</td>" +
                "<td style='color:%s;font-weight:bold'>₹%+,.0f</td></tr>",
                d.date(), d.trades(), d.wins(), d.losses(), clr, d.pnl()));
        }

        StringBuilder tradeRows = new StringBuilder();
        for (OIMomentumBacktestTrade t : trades) {
            String clr = t.win() ? "#27ae60" : "#e74c3c";
            tradeRows.append(String.format(
                "<tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%d</td>" +
                "<td>%.2f</td><td>%.2f</td>" +
                "<td style='color:%s;font-weight:bold'>%+.1f%%</td>" +
                "<td style='color:%s;font-weight:bold'>₹%+,.0f</td>" +
                "<td>%s</td><td>%dm</td></tr>",
                t.date(), t.entryTime(), t.exitTime(), t.direction(), t.strike(),
                t.entryPrice(), t.exitPrice(), clr, t.pnlPct(), clr, t.pnlInr(),
                t.exitReason(), t.holdMinutes()));
        }

        String cumJson = cumPnl.toString();
        String datesJson = days.stream().map(d -> "\"" + d.date() + "\"")
                .collect(Collectors.joining(",", "[", "]"));
        String dailyJson = days.stream().map(d -> String.valueOf(round2(d.pnl())))
                .collect(Collectors.joining(",", "[", "]"));

        return "<!DOCTYPE html><html><head><meta charset='utf-8'>" +
            "<title>OI Momentum Backtest — " + req.underlying() + "</title>" +
            "<script src='https://cdn.jsdelivr.net/npm/chart.js@4.4.0/dist/chart.umd.min.js'></script>" +
            "<style>body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;" +
            "background:#0f1117;color:#e0e0e0;margin:0;padding:20px}" +
            "h1{color:#f0f0f0;margin-bottom:4px}.sub{color:#888;font-size:13px;margin-bottom:24px}" +
            ".grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:14px;margin:24px 0}" +
            ".card{background:#1a1d27;border-radius:10px;padding:18px;border:1px solid #2a2d3d}" +
            ".card .val{font-size:26px;font-weight:700;margin:6px 0 4px}.card .lbl{font-size:11px;color:#888;text-transform:uppercase}" +
            ".pos{color:#27ae60}.neg{color:#e74c3c}.neu{color:#f39c12}" +
            ".wrap{background:#1a1d27;border-radius:10px;padding:18px;margin:14px 0;border:1px solid #2a2d3d}" +
            ".wrap h2{margin:0 0 14px;font-size:15px;color:#ccc}" +
            "table{width:100%;border-collapse:collapse;font-size:12px}" +
            "th{background:#252836;padding:9px 10px;text-align:left;color:#aaa;font-weight:600;border-bottom:1px solid #2a2d3d}" +
            "td{padding:7px 10px;border-bottom:1px solid #1e2030}tr:hover td{background:#252836}" +
            ".cfg{background:#1a2030;border-left:4px solid #3498db;padding:10px 14px;border-radius:4px;margin:14px 0;font-size:12px;color:#aaa}" +
            "</style></head><body>" +
            "<h1>📊 OI Momentum Backtest — " + req.underlying() + "</h1>" +
            "<p class='sub'>" + req.from() + " → " + req.to() +
            " | 1-min bar data | Lot 65 | SL " + config.getStopLossPercent() +
            "% | Trail " + config.getTrailingActivationPercent() + "/" + config.getTrailingGapPercent() +
            "% | Bias≥" + config.getBiasConfidenceThreshold() +
            " | MultiTF=" + config.isMultiTimeframeEnabled() + "</p>" +
            "<div class='cfg'>⚙️ All parameters sourced live from <b>OIMomentumConfig</b> — " +
            "this report always reflects your current application.yml settings.</div>" +
            "<div class='grid'>" +
            card("Net P&L", String.format("<span style='color:%s'>₹%+,.0f</span>", netColor, net)) +
            card("Total Trades", num(m, "totalTrades") + "<br><small style='color:#888'>" + num(m, "tradesPerActiveDay") + "/day active</small>") +
            card("Win Rate", num(m, "winRate") + "%<br><small style='color:#888'>" + num(m, "winCount") + "W / " + num(m, "lossCount") + "L</small>") +
            card("Profit Factor", num(m, "profitFactor")) +
            card("Avg Win", String.format("<span class='pos'>₹%+,.0f</span>", dbl(m, "avgWinInr"))) +
            card("Avg Loss", String.format("<span class='neg'>₹%+,.0f</span>", dbl(m, "avgLossInr"))) +
            card("Max Drawdown", String.format("<span class='neg'>₹%,.0f</span>", dbl(m, "maxDrawdownInr"))) +
            card("Active Days", num(m, "activeDays") + " / " + days.size()) +
            "</div>" +
            "<div class='wrap'><h2>Cumulative P&L (₹)</h2><canvas id='c1' height='70'></canvas></div>" +
            "<div class='wrap'><h2>Daily P&L (₹)</h2><canvas id='c2' height='60'></canvas></div>" +
            "<div class='wrap'><h2>Day-by-Day</h2><table>" +
            "<tr><th>Date</th><th>Trades</th><th>Wins</th><th>Losses</th><th>P&L</th></tr>" +
            dayRows + "</table></div>" +
            "<div class='wrap'><h2>All Trades</h2><table>" +
            "<tr><th>Date</th><th>Entry</th><th>Exit</th><th>Side</th><th>Strike</th>" +
            "<th>Entry ₹</th><th>Exit ₹</th><th>P&L%</th><th>P&L ₹</th><th>Reason</th><th>Hold</th></tr>" +
            tradeRows + "</table></div>" +
            "<script>" +
            "const dates=" + datesJson + ",cum=" + cumJson + ",dpnl=" + dailyJson + ";" +
            "new Chart(document.getElementById('c1'),{type:'line',data:{labels:dates,datasets:[{label:'Cum P&L'," +
            "data:cum,fill:true,backgroundColor:'rgba(39,174,96,0.1)',borderColor:'#27ae60',tension:0.3,pointRadius:2}]}," +
            "options:{plugins:{legend:{display:false}},scales:{x:{ticks:{color:'#888',maxTicksLimit:12},grid:{color:'#1e2030'}}," +
            "y:{ticks:{color:'#888',callback:v=>'₹'+v.toLocaleString()},grid:{color:'#1e2030'}}}}});" +
            "new Chart(document.getElementById('c2'),{type:'bar',data:{labels:dates,datasets:[{label:'Daily P&L'," +
            "data:dpnl,backgroundColor:dpnl.map(v=>v>0?'rgba(39,174,96,0.7)':'rgba(231,76,60,0.7)')}]}," +
            "options:{plugins:{legend:{display:false}},scales:{x:{ticks:{color:'#888',maxTicksLimit:12},grid:{color:'#1e2030'}}," +
            "y:{ticks:{color:'#888',callback:v=>'₹'+v.toLocaleString()},grid:{color:'#1e2030'}}}}});" +
            "</script></body></html>";
    }

    private String card(String label, String value) {
        return "<div class='card'><div class='lbl'>" + label + "</div><div class='val'>" + value + "</div></div>";
    }

    /** Null-safe metric → string (returns "0" when key absent). */
    private String num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString() : "0";
    }

    /** Null-safe metric → double (returns 0.0 when key absent or not a Number). */
    private double dbl(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }
}
