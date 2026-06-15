package com.algo.trade.strategy.oimomentum.v3;

import com.algo.trade.data.ChainSnapshot;
import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * V3 OPERATOR — Multi-strike candidate picker.
 *
 * <p>For each entry signal, scores five candidate strikes and returns them ranked:</p>
 * <ol>
 *   <li>ATM — default baseline.</li>
 *   <li>ATM toward momentum (CE: +1 strike; PE: −1 strike) — higher delta.</li>
 *   <li>Trap strike — from OiSignal.{support,resistance}Strike.</li>
 *   <li>Gamma wall — from MarketContextService.gammaWalls (price magnet).</li>
 *   <li>Max-pain strike — for expiry-day pin plays.</li>
 * </ol>
 *
 * <p>Score formula (per design doc):</p>
 * <pre>
 *   score = delta × 100
 *         + 15 × isTrapStrike
 *         + 10 × isMagnet
 *         − 50 × max(0, spreadPct − 1)
 *         + 5  × log10(openInterest)
 * </pre>
 *
 * <p>Liquidity-filtered candidates ranked by score are returned. Caller (strategy)
 * picks the top one whose quote passes G4.</p>
 */
@Component
public class MultiStrikePicker {

    /** A scored strike candidate. */
    public record Candidate(
            int strike,
            OptionType optionType,
            double delta,
            long openInterest,
            double impliedVol,
            String roleTag,        // "ATM", "ATM±1", "TRAP", "GAMMA_WALL", "MAX_PAIN"
            double score
    ) {}

    /**
     * Build and rank candidates. The chain snapshot is used to fetch per-strike data.
     *
     * @param ix             index
     * @param snapshot       current chain snapshot
     * @param oiSignal       result of ChainSignalAnalyzer
     * @param momentumDir    momentum direction (+1 / −1)
     * @param gammaWallAbove gamma-wall strike above ATM (from MarketContextService)
     * @param gammaWallBelow gamma-wall strike below ATM
     * @param maxPainStrike  max-pain strike (used only for expiry-day plays)
     * @param isExpiryDay    whether today is the resolved expiry for this index
     */
    public List<Candidate> rankCandidates(IndexType ix, ChainSnapshot snapshot,
                                          OiSignal oiSignal, int momentumDir,
                                          int gammaWallAbove, int gammaWallBelow,
                                          int maxPainStrike, boolean isExpiryDay) {
        if (snapshot == null || momentumDir == 0) return List.of();
        int atm = snapshot.atmStrike();
        int interval = ix.strikeInterval();
        OptionType ot = momentumDir > 0 ? OptionType.CE : OptionType.PE;

        // Build candidate strike set (de-duped via simple list — sizes ≤ 5)
        List<Integer> candidateStrikes = new ArrayList<>();
        candidateStrikes.add(atm);
        candidateStrikes.add(atm + (momentumDir > 0 ? interval : -interval));  // ATM±1 toward momentum
        // Trap strike
        int trap = momentumDir > 0 ? oiSignal.resistanceStrike() : oiSignal.supportStrike();
        if (trap > 0 && !candidateStrikes.contains(trap)) candidateStrikes.add(trap);
        // Gamma wall in move direction
        int wall = momentumDir > 0 ? gammaWallAbove : gammaWallBelow;
        if (wall > 0 && !candidateStrikes.contains(wall)) candidateStrikes.add(wall);
        // Max-pain (expiry only)
        if (isExpiryDay && maxPainStrike > 0 && !candidateStrikes.contains(maxPainStrike)) {
            candidateStrikes.add(maxPainStrike);
        }

        List<Candidate> scored = new ArrayList<>();
        for (int strike : candidateStrikes) {
            ChainSnapshot.StrikeData s = findStrike(snapshot, strike);
            if (s == null) continue;
            double delta = ot == OptionType.CE ? s.ceDelta() : Math.abs(s.peDelta());
            long oi = ot == OptionType.CE ? s.ceOI() : s.peOI();
            double iv = ot == OptionType.CE ? s.ceIV() : s.peIV();
            double bid = ot == OptionType.CE ? s.ceBid() : s.peBid();
            double ask = ot == OptionType.CE ? s.ceAsk() : s.peAsk();
            double mid = (bid + ask) / 2.0;
            double spreadPct = mid > 0 ? (ask - bid) / mid * 100.0 : 0;

            String role;
            double bonus = 0;
            if (strike == atm) role = "ATM";
            else if (Math.abs(strike - atm) == interval) role = "ATM±1";
            else if (strike == trap) { role = "TRAP"; bonus += 15; }
            else if (strike == wall) { role = "GAMMA_WALL"; bonus += 10; }
            else if (strike == maxPainStrike) { role = "MAX_PAIN"; bonus += 10; }
            else role = "OTHER";

            double score = delta * 100
                    + bonus
                    - 50 * Math.max(0, spreadPct - 1.0)
                    + 5 * Math.log10(Math.max(oi, 1));

            scored.add(new Candidate(strike, ot, delta, oi, iv, role, score));
        }
        scored.sort(Comparator.comparingDouble(Candidate::score).reversed());
        return scored;
    }

    private static ChainSnapshot.StrikeData findStrike(ChainSnapshot snapshot, int strike) {
        for (ChainSnapshot.StrikeData s : snapshot.strikes()) {
            if (s.strike() == strike) return s;
        }
        return null;
    }
}
