package com.algo.trade.strategy;

import com.algo.trade.regime.RegimeFilter.MarketRegime;
import com.algo.trade.strategy.AlgoFlowOrchestrator.SessionWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;

/**
 * Computes dynamic strike offset from ATM based on market regime and session window.
 *
 * <p>The offset determines how far OTM (or ATM) the system should select strikes:
 * <ul>
 *   <li>+2 = two strikes OTM (aggressive, high-conviction conditions)</li>
 *   <li>+1 = one strike OTM</li>
 *   <li> 0 = ATM</li>
 *   <li>-1 = block sentinel — entry should be skipped entirely</li>
 * </ul>
 *
 * <p>The lookup table is a static {@code EnumMap<MarketRegime, EnumMap<SessionWindow, Integer>>}
 * initialized at construction, making the function pure, deterministic, and trivially testable.
 */
@Component
public class RegimeAwareStrikeSelector {

    private static final Logger log = LoggerFactory.getLogger(RegimeAwareStrikeSelector.class);

    private static final EnumMap<MarketRegime, EnumMap<SessionWindow, Integer>> OFFSET_TABLE;

    static {
        OFFSET_TABLE = new EnumMap<>(MarketRegime.class);

        // IDEAL regime: aggressive OTM in momentum windows, ATM in chop
        EnumMap<SessionWindow, Integer> ideal = new EnumMap<>(SessionWindow.class);
        ideal.put(SessionWindow.MORNING_MOMENTUM, 1);
        ideal.put(SessionWindow.POST_OPEN, 1);
        ideal.put(SessionWindow.MIDDAY_CHOP, 0);
        ideal.put(SessionWindow.AFTERNOON, 0);
        ideal.put(SessionWindow.PRE_EXPIRY, 2);
        ideal.put(SessionWindow.GAMMA_SCALPING, 1);
        OFFSET_TABLE.put(MarketRegime.IDEAL, ideal);

        // GOOD regime: slightly less aggressive
        EnumMap<SessionWindow, Integer> good = new EnumMap<>(SessionWindow.class);
        good.put(SessionWindow.MORNING_MOMENTUM, 1);
        good.put(SessionWindow.POST_OPEN, 0);
        good.put(SessionWindow.MIDDAY_CHOP, 0);
        good.put(SessionWindow.AFTERNOON, 0);
        good.put(SessionWindow.PRE_EXPIRY, 1);
        good.put(SessionWindow.GAMMA_SCALPING, 0);
        OFFSET_TABLE.put(MarketRegime.GOOD, good);

        // NEUTRAL regime: always ATM
        EnumMap<SessionWindow, Integer> neutral = new EnumMap<>(SessionWindow.class);
        neutral.put(SessionWindow.MORNING_MOMENTUM, 0);
        neutral.put(SessionWindow.POST_OPEN, 0);
        neutral.put(SessionWindow.MIDDAY_CHOP, 0);
        neutral.put(SessionWindow.AFTERNOON, 0);
        neutral.put(SessionWindow.PRE_EXPIRY, 0);
        neutral.put(SessionWindow.GAMMA_SCALPING, 0);
        OFFSET_TABLE.put(MarketRegime.NEUTRAL, neutral);

        // RISKY regime: ATM in momentum windows, block in chop/afternoon
        EnumMap<SessionWindow, Integer> risky = new EnumMap<>(SessionWindow.class);
        risky.put(SessionWindow.MORNING_MOMENTUM, 0);
        risky.put(SessionWindow.POST_OPEN, 0);
        risky.put(SessionWindow.MIDDAY_CHOP, -1);
        risky.put(SessionWindow.AFTERNOON, -1);
        risky.put(SessionWindow.PRE_EXPIRY, -1);
        risky.put(SessionWindow.GAMMA_SCALPING, -1);
        OFFSET_TABLE.put(MarketRegime.RISKY, risky);

        // DANGER regime: block all entries
        EnumMap<SessionWindow, Integer> danger = new EnumMap<>(SessionWindow.class);
        danger.put(SessionWindow.MORNING_MOMENTUM, -1);
        danger.put(SessionWindow.POST_OPEN, -1);
        danger.put(SessionWindow.MIDDAY_CHOP, -1);
        danger.put(SessionWindow.AFTERNOON, -1);
        danger.put(SessionWindow.PRE_EXPIRY, -1);
        danger.put(SessionWindow.GAMMA_SCALPING, -1);
        OFFSET_TABLE.put(MarketRegime.DANGER, danger);
    }

    /**
     * Compute strike offset from ATM based on market regime and session window.
     *
     * @param regime  current market regime from {@link com.algo.trade.regime.RegimeFilter}
     * @param session current session window from {@link AlgoFlowOrchestrator}
     * @return offset >= 0 for valid entry (0=ATM, +1=1 OTM, +2=2 OTM),
     *         or -1 as block sentinel (entry should be skipped)
     */
    public int computeStrikeOffset(MarketRegime regime, SessionWindow session) {
        EnumMap<SessionWindow, Integer> sessionMap = OFFSET_TABLE.get(regime);
        if (sessionMap == null) {
            log.warn("No offset mapping for regime={} — defaulting to block", regime);
            return -1;
        }

        Integer offset = sessionMap.get(session);
        if (offset == null) {
            log.warn("No offset mapping for regime={}, session={} — defaulting to block", regime, session);
            return -1;
        }

        log.debug("StrikeSelector: regime={}, session={} → offset={}", regime, session, offset);
        return offset;
    }
}
