import java.sql.*;
import java.util.*;

/**
 * STANDALONE replay of the NEW chop-regime / exit-grace(COE-defer) / fade logic against the local
 * atm-microstructure tape. NEW FILE — modifies no existing class and does not touch OIMomentumStrategy.
 *
 * APPROACH 2 (1:1 re-implementation, explicitly labelled). Approach 1 (reflection into the live
 * OIMomentumStrategy) is NOT viable: detectEntry/managePosition are private and call ~15 live beans
 * (MarketDataService, ExecutionEngine, TradeRepository, momentumDetector rolling windows, v3 pipeline,
 * operator framework, OiDivergenceMonitor, ConvictionOverrideEngine ...) that cannot be fed from this
 * tape; the new exit-grace is INLINE inside managePosition (not an isolable method). So the exact new
 * decision predicates are ported here 1:1 from the source and run on the tape.
 *
 * FAITHFULNESS PER PIECE (be honest in the report):
 *  • OI_WRITER_STOP  -> FAITHFUL. Ported from OiDivergenceMonitor.evaluateHeldExit/evaluate: held-strike
 *    OI rose >= oi-rise-pct(0.8%) over window(60s) AND own premium fell, confirmed >= confirm-ticks(3),
 *    armed heldSec>=arm-sec(60), premNow<entry. All inputs (per-strike oi + ltp/sec) are in the tape.
 *  • COE isReversing -> FAITHFUL. Ported from ConvictionOverrideEngine.isReversing/isFakeMove:
 *    spoofActive OR (isAbsorption AND aggressor>0 AND |signedVolume|>=absorbMin[NIFTY 15000/SENSEX 1500]).
 *    The tape carries the LIVE-computed isAbsorption/spoofActive/aggressor/signedVolume/bookImbalance.
 *  • exit-grace COE-defer -> FAITHFUL (my new logic, 1:1): within grace-window(240s) AND chopRegime AND
 *    profit > -hard-floor(8%) AND !coeReversing -> DEFER the writer-stop; else fire it.
 *  • chopRegime -> PARTIAL. range30m from an ATM put-call-parity spot reconstruction (F=K+CE-PE). VIX is
 *    ABSENT from the tape (no local source); today's ATM-IV ran ~9-11% (< 13) per greeks-verify, so the
 *    VIX<13 leg is treated as satisfied (assumed-vix param, default 11). chopRegime≈(range30m<0.40%).
 *  • entries + chase-classification + fade -> PROXY (labelled). The real entry detectors (EARLY_OI_VELOCITY,
 *    SUSTAINED_DRIFT, RANGE_EDGE_FADE) need chain OI/PCR/drift/operator not in this tape. Entries here are a
 *    COE-confirm long proxy so there is a held book to exercise the exit logic; "chase" = entered into a
 *    rising-OI+rising-premium momentum (EARLY_OI_VELOCITY-like); fade = range-edge in chop.
 *
 * Run: java -cp <duckdb_jdbc.jar> ChopRegimeReplay.java <csv> <on|off> [assumedVix]
 */
public class ChopRegimeReplay {
    // ── ported thresholds (1:1) ──
    static final int OWS_WINDOW_SEC = 60;      // oi-divergence.window-sec
    static final double OWS_OI_RISE_PCT = 0.8; // oi-divergence.oi-rise-pct
    static final int OWS_CONFIRM = 3;          // oi-divergence.confirm-ticks
    static final int OWS_ARM_SEC = 60;         // oi-divergence.arm-sec
    static final double COE_ABSORB_NIFTY = 15000, COE_ABSORB_SENSEX = 1500; // conviction-override.absorb-min-signed-vol-*
    static final int GRACE_WINDOW_SEC = 240;   // oi-momentum.exit-grace.window-sec
    static final double GRACE_HARD_FLOOR = 8.0;// oi-momentum.exit-grace.hard-floor-pct
    static final double CHOP_VIX_MAX = 13.0;   // oi-momentum.chop-regime.vix-max
    static final double CHOP_RANGE30M_MAX = 0.40; // oi-momentum.chop-regime.range30m-max
    static final double FADE_TARGET = 8.0, FADE_STOP = 5.0; // oi-momentum.fade-mode.target/stop-pct
    static final double FADE_EDGE_BAND = 0.15; // range-edge band for fade proxy
    // entry proxy + common baseline exit
    static final double ENTRY_IMB_MIN = 0.25;  // COE confirmsLong imbalance-min
    static final long COOLDOWN_SEC = 60, MAX_HOLD_SEC = 1800;
    static final int MAX_ENTRIES_STRIKE = 5;
    static final double SL_PCT = -12, TRAIL_ACT = 4, TRAIL_GAP = 8, RT_COST = 50;
    static final double CHASE_OI_VEL = 0.5;    // EARLY_OI_VELOCITY-like proxy: 60s OI rise %

    static boolean FLAGS_ON = true;
    static double ASSUMED_VIX = 11.0;
    static boolean VIX_GATE_OK; // (0<vix<13)

    static double absorbMin(String idx){ return idx.equals("SENSEX")?COE_ABSORB_SENSEX:COE_ABSORB_NIFTY; }
    static int lot(String idx){ return idx.equals("SENSEX")?20:65; }

    // per-index chop-by-second (carry-forward). key idx -> TreeMap<sec,Boolean>
    static final Map<String,TreeMap<Long,Boolean>> chopByIdx = new HashMap<>();
    static boolean chopAt(String idx, long ts){
        TreeMap<Long,Boolean> m = chopByIdx.get(idx); if(m==null) return false;
        Map.Entry<Long,Boolean> e = m.floorEntry(ts); return e!=null && e.getValue();
    }

    static final class Trade { String idx,typ,reason; int strike; long entryTs,exitTs; double entryAsk,exitBid; int lot;
        boolean deferred; boolean fade; boolean chase; double pnl(){ return (exitBid-entryAsk)*lot - RT_COST; } }

    public static void main(String[] args) throws Exception {
        String CSV = args[0];
        FLAGS_ON = args.length<2 || args[1].equalsIgnoreCase("on");
        if(args.length>2) ASSUMED_VIX = Double.parseDouble(args[2]);
        VIX_GATE_OK = ASSUMED_VIX>0 && ASSUMED_VIX<CHOP_VIX_MAX;
        Class.forName("org.duckdb.DuckDBDriver");
        try(Connection c=DriverManager.getConnection("jdbc:duckdb:"); Statement st=c.createStatement()){
            st.execute("PRAGMA threads=4");
            String win = " to_timestamp(exchangeTsEpochSec) BETWEEN TIMESTAMP '2026-07-06 03:45:00' AND TIMESTAMP '2026-07-06 10:00:00' ";
            buildChopMap(st, CSV, win);
            List<Trade> trades = replay(st, CSV, win);
            fadePass(st, CSV, win, trades);
            report(trades);
        }
    }

    // ── Pass 1: ATM put-call-parity spot per second -> rolling 30m range -> chop flag ──
    static void buildChopMap(Statement st, String CSV, String win) throws Exception {
        String q =
          "WITH piv AS (SELECT index idx, exchangeTsEpochSec ts, strike,"
        + "   max(ltp) FILTER(WHERE optionType='CE') ce, max(ltp) FILTER(WHERE optionType='PE') pe"
        + "  FROM read_csv_auto('"+CSV+"') WHERE ltp>0 AND "+win+" GROUP BY index, exchangeTsEpochSec, strike),"
        + " atm AS (SELECT idx, ts, (strike + ce - pe) spot, row_number() OVER (PARTITION BY idx,ts ORDER BY abs(ce-pe)) rn"
        + "  FROM piv WHERE ce>0 AND pe>0)"
        + " SELECT idx, ts, spot FROM atm WHERE rn=1 ORDER BY idx, ts";
        Map<String,ArrayDeque<long[]>> winByIdx = new HashMap<>(); // idx -> deque of [ts, spot*1000]
        Map<String,double[]> lastSpot = new HashMap<>();
        try(ResultSet r=st.executeQuery(q)){
            while(r.next()){
                String idx=r.getString(1); long ts=r.getLong(2); double spot=r.getDouble(3);
                if(spot<=0) continue;
                chopByIdx.computeIfAbsent(idx,k->new TreeMap<>());
                ArrayDeque<long[]> dq = winByIdx.computeIfAbsent(idx,k->new ArrayDeque<>());
                dq.addLast(new long[]{ts, Math.round(spot*100)});
                while(!dq.isEmpty() && ts - dq.peekFirst()[0] > 1800) dq.pollFirst();
                long hi=Long.MIN_VALUE, lo=Long.MAX_VALUE;
                for(long[] p: dq){ hi=Math.max(hi,p[1]); lo=Math.min(lo,p[1]); }
                double range = lo>0 ? (hi-lo)/(double)lo*100.0 : 0;
                boolean chop = VIX_GATE_OK && range>0 && range<CHOP_RANGE30M_MAX;
                chopByIdx.get(idx).put(ts, chop);
            }
        }
    }

    // ── Pass 2: per-(idx,strike,type) long replay with the NEW exit logic ──
    static List<Trade> replay(Statement st, String CSV, String win) throws Exception {
        String q = "SELECT index idx, strike, optionType typ, exchangeTsEpochSec ts, ltp, bestBid bid, bestAsk ask,"
          + " oi, bookImbalance bi, signedVolume sv, aggressor agg, isAbsorption absb, spoofActive spf"
          + " FROM read_csv_auto('"+CSV+"') WHERE ltp>0 AND bestBid>0 AND bestAsk>0 AND "+win
          + " ORDER BY index, strike, optionType, exchangeTsEpochSec";
        List<Trade> out = new ArrayList<>();
        String curKey=null; String cidx=null,ctyp=null; int cstrike=0;
        ArrayDeque<double[]> w = new ArrayDeque<>(); // [ts, oi, prem(ltp)]
        boolean inLong=false; double entryAsk=0, peakBid=0; long entryTs=0, cooldownUntil=0; boolean curChase=false, curDeferred=false;
        int streak=0, entriesKey=0; double lastBid=0; long lastTs=0;
        // stream
        try(ResultSet r=st.executeQuery(q)){
            while(r.next()){
                String idx=r.getString("idx"), typ=r.getString("typ"); int strike=r.getInt("strike"); long ts=r.getLong("ts");
                double ltp=r.getDouble("ltp"), bid=r.getDouble("bid"), ask=r.getDouble("ask");
                long oi=r.getLong("oi"); double bi=r.getDouble("bi"); long sv=r.getLong("sv"); int agg=r.getInt("agg");
                boolean absb=r.getBoolean("absb"), spf=r.getBoolean("spf");
                String key=idx+"|"+strike+"|"+typ;
                if(!key.equals(curKey)){
                    if(inLong){ Trade t=mk(cidx,ctyp,cstrike,entryTs,lastTs,entryAsk,lastBid>0?lastBid:entryAsk,"EOD",curChase,curDeferred); out.add(t); }
                    curKey=key; cidx=idx; ctyp=typ; cstrike=strike; w.clear(); inLong=false; streak=0; entriesKey=0; cooldownUntil=0; curChase=false; curDeferred=false;
                }
                // maintain 60s window of [ts,oi,prem]
                w.addLast(new double[]{ts, oi, ltp});
                while(!w.isEmpty() && ts - w.peekFirst()[0] > OWS_WINDOW_SEC) w.pollFirst();
                double[] old = w.peekFirst();
                double oi0 = old[1], prem0 = old[2];
                double oiRise = oi0>0 ? (oi - oi0)/oi0*100.0 : 0;
                boolean premFell = ltp < prem0;
                lastBid=bid; lastTs=ts;

                if(!inLong){
                    boolean coeConfirm = bi>=ENTRY_IMB_MIN && sv>0;              // COE confirmsLong proxy
                    if(coeConfirm && ts>=cooldownUntil && entriesKey<MAX_ENTRIES_STRIKE){
                        boolean chase = oiRise>=CHASE_OI_VEL && ltp>prem0;        // EARLY_OI_VELOCITY-like proxy
                        boolean chop = chopAt(idx, ts);
                        if(FLAGS_ON && chase && chop){ CHOP_SUPPRESSED++; continue; } // chase-off in chop
                        inLong=true; entryAsk=ask; entryTs=ts; peakBid=bid; streak=0; entriesKey++; curChase=chase; curDeferred=false;
                    }
                    continue;
                }
                // ── managing a long ──
                long heldSec = ts-entryTs; peakBid=Math.max(peakBid,bid);
                double profitPct = entryAsk>0 ? (bid-entryAsk)/entryAsk*100.0 : 0;
                // hard SL / trail / max-hold (common baseline, both ON and OFF)
                if(profitPct<=SL_PCT){ out.add(mk(cidx,ctyp,cstrike,entryTs,ts,entryAsk,bid,"STOP_LOSS",curChase,curDeferred)); inLong=false; cooldownUntil=ts+COOLDOWN_SEC; continue; }
                double peakPct=(peakBid-entryAsk)/entryAsk*100.0;
                if(peakPct>=TRAIL_ACT && profitPct<=peakPct-TRAIL_GAP){ out.add(mk(cidx,ctyp,cstrike,entryTs,ts,entryAsk,bid,"TRAIL",curChase,curDeferred)); inLong=false; cooldownUntil=ts+COOLDOWN_SEC; continue; }
                if(heldSec>=MAX_HOLD_SEC){ out.add(mk(cidx,ctyp,cstrike,entryTs,ts,entryAsk,bid,"MAX_HOLD",curChase,curDeferred)); inLong=false; cooldownUntil=ts+COOLDOWN_SEC; continue; }
                // OI_WRITER_STOP (ported): oiRise>=0.8 & premFell & armed & below-entry, confirm>=3
                boolean fireable = oiRise>=OWS_OI_RISE_PCT && premFell && ltp<entryAsk;
                streak = fireable ? streak+1 : 0;
                boolean owsConfirmed = fireable && streak>=OWS_CONFIRM && heldSec>=OWS_ARM_SEC;
                if(owsConfirmed){
                    WRITER_STOP_SIGNALS++;
                    boolean coeRev = spf || (absb && agg>0 && Math.abs(sv)>=absorbMin(idx)); // isReversing (ported)
                    boolean chop = chopAt(idx, ts);
                    boolean defer = FLAGS_ON && heldSec<GRACE_WINDOW_SEC && profitPct>-GRACE_HARD_FLOOR && !coeRev && chop;
                    if(defer){ DEFERRALS_TICKS++; if(!curDeferred){ curDeferred=true; DEFERRED_POSITIONS++; } continue; } // hold, keep managing
                    String rsn = coeRev ? "OI_WRITER_STOP(coe)" : "OI_WRITER_STOP";
                    out.add(mk(cidx,ctyp,cstrike,entryTs,ts,entryAsk,bid,rsn,curChase,curDeferred)); inLong=false; cooldownUntil=ts+COOLDOWN_SEC; continue;
                }
            }
            if(inLong){ out.add(mk(cidx,ctyp,cstrike,entryTs,lastTs,entryAsk,lastBid>0?lastBid:entryAsk,"EOD",curChase,curDeferred)); }
        }
        return out;
    }

    // ── Fade pass (PROXY): range-edge fade in chop, 8%/5% bracket on the ATM option ──
    static void fadePass(Statement st, String CSV, String win, List<Trade> out){
        if(!FLAGS_ON) return; // fade-mode only adds trades when flags ON
        try {
            // reconstruct per-second spot + ATM strike + its CE/PE bid/ask, then detect range-edge fades in chop
            String q = "WITH piv AS (SELECT index idx, exchangeTsEpochSec ts, strike,"
              + " max(ltp) FILTER(WHERE optionType='CE') ce, max(ltp) FILTER(WHERE optionType='PE') pe,"
              + " max(bestBid) FILTER(WHERE optionType='CE') ceb, max(bestAsk) FILTER(WHERE optionType='CE') cea,"
              + " max(bestBid) FILTER(WHERE optionType='PE') peb, max(bestAsk) FILTER(WHERE optionType='PE') pea"
              + " FROM read_csv_auto('"+CSV+"') WHERE ltp>0 AND "+win+" GROUP BY index, exchangeTsEpochSec, strike),"
              + " atm AS (SELECT idx, ts, strike, (strike+ce-pe) spot, cea,ceb,pea,peb,"
              + "  row_number() OVER (PARTITION BY idx,ts ORDER BY abs(ce-pe)) rn FROM piv WHERE ce>0 AND pe>0)"
              + " SELECT idx, ts, spot, strike, cea,ceb,pea,peb FROM atm WHERE rn=1 ORDER BY idx, ts";
            Map<String,ArrayDeque<long[]>> wq=new HashMap<>();
            // active fade per idx
            Map<String,double[]> active=new HashMap<>(); // idx -> [entryAsk, ts, dir(+1 CE / -1 PE), strike]
            Map<String,Long> cooldown=new HashMap<>();
            try(ResultSet r=st.executeQuery(q)){
                while(r.next()){
                    String idx=r.getString(1); long ts=r.getLong(2); double spot=r.getDouble(3); int strike=r.getInt(4);
                    double cea=r.getDouble(5),ceb=r.getDouble(6),pea=r.getDouble(7),peb=r.getDouble(8);
                    ArrayDeque<long[]> dq=wq.computeIfAbsent(idx,k->new ArrayDeque<>());
                    dq.addLast(new long[]{ts,Math.round(spot*100)});
                    while(!dq.isEmpty() && ts-dq.peekFirst()[0]>1800) dq.pollFirst();
                    long hi=Long.MIN_VALUE,lo=Long.MAX_VALUE; for(long[] p:dq){hi=Math.max(hi,p[1]);lo=Math.min(lo,p[1]);}
                    double pos = hi>lo ? (spot*100-lo)/(double)(hi-lo) : 0.5;
                    boolean chop = chopAt(idx, ts);
                    double[] act = active.get(idx);
                    if(act!=null){ // manage open fade: 8/5 bracket, current option bid/ask
                        double entryA=act[0]; int dir=(int)act[2];
                        double bidNow = dir>0?ceb:peb;
                        double pnlPct = entryA>0?(bidNow-entryA)/entryA*100.0:0;
                        if(pnlPct>=FADE_TARGET || pnlPct<=-FADE_STOP || ts-(long)act[1]>=MAX_HOLD_SEC){
                            Trade t=new Trade(); t.idx=idx; t.typ=dir>0?"CE":"PE"; t.strike=(int)act[3]; t.entryTs=(long)act[1]; t.exitTs=ts;
                            t.entryAsk=entryA; t.exitBid=bidNow; t.lot=lot(idx); t.fade=true;
                            t.reason=pnlPct>=FADE_TARGET?"FADE_TARGET":pnlPct<=-FADE_STOP?"FADE_STOP":"FADE_EOD";
                            out.add(t); active.remove(idx); cooldown.put(idx, ts+120);
                        }
                        continue;
                    }
                    if(!chop) continue;
                    if(cooldown.getOrDefault(idx,0L)>ts) continue;
                    // range-edge fade, drift-aligned toward mean: top edge -> PE (fade down), bottom -> CE (fade up)
                    if(pos>=1.0-FADE_EDGE_BAND && pea>0){ active.put(idx,new double[]{pea,ts,-1,strike}); FADE_ENTRIES++; }
                    else if(pos<=FADE_EDGE_BAND && cea>0){ active.put(idx,new double[]{cea,ts,+1,strike}); FADE_ENTRIES++; }
                }
            }
        } catch(Exception e){ System.out.println("  [fade pass skipped: "+e+"]"); }
    }

    static Trade mk(String idx,String typ,int strike,long ets,long xts,double ea,double xb,String rsn,boolean chase,boolean deferred){
        Trade t=new Trade(); t.idx=idx;t.typ=typ;t.strike=strike;t.entryTs=ets;t.exitTs=xts;t.entryAsk=ea;t.exitBid=xb;t.reason=rsn;t.lot=lot(idx);t.chase=chase;t.deferred=deferred; return t; }

    // counters
    static long CHOP_SUPPRESSED=0, WRITER_STOP_SIGNALS=0, DEFERRALS_TICKS=0, DEFERRED_POSITIONS=0, FADE_ENTRIES=0;

    static void report(List<Trade> ts){
        System.out.println("\n================ ChopRegimeReplay [flags="+(FLAGS_ON?"ON (new logic)":"OFF (baseline)")
            +", assumedVix="+ASSUMED_VIX+(VIX_GATE_OK?" (<13 => chop=range30m<0.40%)":" (>=13 => chop OFF)")+"] ================");
        System.out.println("(entries=COE-confirm long PROXY; OI_WRITER_STOP + COE-defer = FAITHFUL 1:1 port; spot=ATM parity)");
        double gross=0,net=0; int win=0; Map<String,int[]> byReason=new TreeMap<>(); // reason -> [n, wins]
        double deferredNet=0; int deferredN=0, deferredRecovered=0, deferredHardFloor=0, deferredOther=0;
        double fadeNet=0; int fadeN=0, fadeWin=0;
        for(Trade t: ts){ double n=t.pnl(); gross+=(t.exitBid-t.entryAsk)*t.lot; net+=n; if(n>0)win++;
            byReason.computeIfAbsent(t.reason,k->new int[2]); byReason.get(t.reason)[0]++; if(n>0) byReason.get(t.reason)[1]++;
            if(t.fade){ fadeN++; fadeNet+=n; if(n>0)fadeWin++; }
            if(t.deferred){ deferredN++; deferredNet+=n; double pp=(t.exitBid-t.entryAsk)/t.entryAsk*100.0;
                if(pp>=0) deferredRecovered++; else if(pp<=-GRACE_HARD_FLOOR) deferredHardFloor++; else deferredOther++; }
        }
        System.out.printf("ENTRIES taken=%d   CHOP-SUPPRESSED(chase-off)=%d%n", ts.size()-fadeN, CHOP_SUPPRESSED);
        System.out.printf("OI_WRITER_STOP confirmed signals=%d%n", WRITER_STOP_SIGNALS);
        System.out.printf("  DEFERRED to COE: positions=%d  (defer-ticks=%d)%n", DEFERRED_POSITIONS, DEFERRALS_TICKS);
        System.out.printf("  of deferred positions -> recovered(exit>=0)=%d   hit-hard-floor(<=-8%%)=%d   other-small-loss=%d%n",
            deferredRecovered, deferredHardFloor, deferredOther);
        System.out.printf("FADE (CHOP_FADE) trades=%d  net=Rs%.0f  win%%=%.1f%n", fadeN, fadeNet, fadeN>0?100.0*fadeWin/fadeN:0);
        System.out.println("-- by exit reason --");
        for(var e: byReason.entrySet()) System.out.printf("   %-22s n=%-5d win%%=%.1f%n", e.getKey(), e.getValue()[0], 100.0*e.getValue()[1]/e.getValue()[0]);
        System.out.printf("== TOTAL trades=%d  grossRs=%.0f  netRs=%.0f  win%%=%.1f ==%n", ts.size(), gross, net, ts.isEmpty()?0:100.0*win/ts.size());
    }
}
