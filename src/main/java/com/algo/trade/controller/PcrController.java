package com.algo.trade.controller;

import com.algo.trade.domain.IndexType;
import com.algo.trade.marketdata.PcrCalculator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PCR API — per-index full-chain Put-Call Ratio.
 *
 *   GET /api/pcr/intraday → today's PCR series per index (for the daily chart, 09:00–15:30)
 *
 * Response shape:
 * {
 *   "NIFTY":     { "latest": 1.234, "series": [ {"time":"09:18","pcr":1.21}, ... ] },
 *   "BANKNIFTY": { ... },
 *   "SENSEX":    { ... }
 * }
 */
@RestController
@RequestMapping("/api/pcr")
public class PcrController {

    private final PcrCalculator pcrCalculator;

    public PcrController(PcrCalculator pcrCalculator) {
        this.pcrCalculator = pcrCalculator;
    }

    @GetMapping("/intraday")
    public Map<String, Object> intraday() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (IndexType idx : PcrCalculator.TRACKED_INDICES) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("latest", pcrCalculator.getPcr(idx));
            entry.put("series", pcrCalculator.getIntradaySeries(idx));
            out.put(idx.name(), entry);
        }
        return out;
    }
}
