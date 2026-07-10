import java.sql.*;
import java.util.*;

/**
 * COE-ALONE backtest on the local L1 microstructure history. Run locally (not on prod).
 * args[0]=archive dir, args[1]=07-03 csv.
 *
 * HONEST SCOPE — read before trusting any number:
 *  • Spoof leg EXCLUDED entirely (history is L1 — no per-level order counts).
 *  • Book imbalance is an L1 proxy (bidQty vs askQty), not the shipped 5-level DepthSnapshot.
 *  • The COE is a GATE on the CTO, not an entry generator. To produce "trades", we run it standalone:
 *    ENTER long on the COE genuine-move confirm, EXIT on the COE reversal (absorption-against). This
 *    measures whether the COE's own definition has edge — NOT the deployed gate behaviour.
 *  • Fills are REALISTIC: buy at bestAsk, sell at bestBid (pay the spread both ways) + a fixed round-trip
 *    cost. No SL/target/trailing (those aren't COE) — a position with no COE reversal is force-closed EOD.
 */
public class CoeBacktest {
    // CTO tape legs
    static final int VOL_WIN = 30, THR_WIN = 60;
    static final double VOL_RATIO_MIN = 1.5, THRUST_PCT_MIN = 2.0; static final long MIN_VOLUME = 500;
    // COE legs
    static final double IMBALANCE_MIN = 0.25, ABSORB_FLAT_PCT = 0.2;
    static final long MIN_HOLD_SEC = 3, COOLDOWN_SEC = 60, MAX_HOLD_SEC = 1800; // 30-min scalp cap
    static final double ROUND_TRIP_COST_RS = 50.0;
    static final int CTO_MAX_ADDS = 1;   // CTO cap: base entry + this many re-entries per strike/day
    // v3 (user-requested run): trend-riding exit (SL+trail) + cap entries/strike/day + double-tick confirmation.
    static final double SL_PCT = -12, TRAIL_ACTIVATE_PCT = 4, TRAIL_GAP_PCT = 8;
    static int MAX_ENTRIES = 5, CONFIRM_TICKS = 2; // v3, overridable via args[3]=maxEntries args[4]=confirmTicks
    // mode: "cto" = CTO+COE (base + capped re-entry above last exit); "coe" = COE-alone (unlimited re-entry);
    //       "v3" = cap MAX_ENTRIES/strike/day + N-consecutive-tick confirm + trend-riding SL/trail exit
    static String MODE = "cto";

    static double absorbBar(String idx){ return switch(idx){ case "SENSEX"->1500; case "BANKNIFTY"->1000; default->15000; }; }
    static int lotSize(String idx){ return switch(idx){ case "SENSEX"->20; case "BANKNIFTY"->30; default->65; }; }

    static final class Trade { String dt,idx,type; int strike; long entryTs,exitTs; double entryAsk,exitBid; String reason; int lot; }

    public static void main(String[] a) throws Exception {
        String ARC=a[0], CSV=a[1];
        if(a.length>2) MODE=a[2];
        if(a.length>3) MAX_ENTRIES=Integer.parseInt(a[3]);
        if(a.length>4) CONFIRM_TICKS=Integer.parseInt(a[4]);
        Class.forName("org.duckdb.DuckDBDriver");
        List<Trade> trades = new ArrayList<>();
        try(Connection c=DriverManager.getConnection("jdbc:duckdb:");Statement s=c.createStatement()){
            s.execute("PRAGMA threads=4");
            String sql =
              "SELECT dt, idx, strike, optionType, ts, ltp, bestBid, bestAsk, bidQty, askQty, cumVolume FROM ("
              +" SELECT month||'-'||day dt, \"index\" idx, strike, optionType, exchangeTsEpochSec ts, ltp, bestBid, bestAsk, bidQty, askQty, cumVolume"
              +" FROM read_parquet('"+ARC+"/**/*.parquet', hive_partitioning=1) WHERE ltp>0"
              +" UNION ALL"
              +" SELECT '07-03' dt, index idx, strike, optionType, exchangeTsEpochSec ts, ltp, bestBid, bestAsk, bidQty, askQty, cumVolume"
              +" FROM read_csv_auto('"+CSV+"') WHERE ltp>0"
              +") ORDER BY dt, idx, strike, optionType, ts";
            ResultSet r=s.executeQuery(sql);

            // per-strike state
            String curKey=null; String cdt=null,cidx=null,ctype=null; int cstrike=0;
            ArrayDeque<double[]> win=new ArrayDeque<>(); // {ts, dv, ltp}
            long prevCumVol=-1; double prevLtp=0; int lastSign=0;
            boolean inLong=false; double entryAsk=0; long entryTs=0, cooldownUntil=0;
            double lastBid=0, lastLtp=0; long lastTs=0;
            int entriesThisGroup=0; double lastExitBid=0; // CTO: cap re-entries + churn (re-buy above last exit)
            double peak=0; int fireStreak=0, entriesToday=0; // v3: peak-for-trail + consec-fireable + per-strike/day entry count

            while(r.next()){
                String dt=r.getString(1), idx=r.getString(2), type=r.getString(4);
                int strike=r.getInt(3); long ts=r.getLong(5);
                double ltp=r.getDouble(6), bid=r.getDouble(7), ask=r.getDouble(8);
                long bq=r.getLong(9), aq=r.getLong(10), cum=r.getLong(11);
                String key=dt+"|"+idx+"|"+strike+"|"+type;
                if(!key.equals(curKey)){
                    // close any open position on the previous strike at its last seen bid (EOD)
                    if(inLong){ Trade t=mk(cdt,cidx,ctype,cstrike,entryTs,lastTs,entryAsk,lastBid>0?lastBid:lastLtp,"EOD",lotSize(cidx)); trades.add(t); }
                    curKey=key; cdt=dt; cidx=idx; ctype=type; cstrike=strike;
                    win.clear(); prevCumVol=-1; prevLtp=0; lastSign=0; inLong=false; cooldownUntil=0;
                    entriesThisGroup=0; lastExitBid=0; peak=0; fireStreak=0; entriesToday=0;
                }
                // Lee-Ready
                long dv = (prevCumVol<0 || cum<prevCumVol)?0:(cum-prevCumVol);
                int sign;
                if(bid>0&&ask>0){ if(ltp>=ask) sign=1; else if(ltp<=bid) sign=-1; else sign= ltp>prevLtp?1:ltp<prevLtp?-1:lastSign; }
                else sign= ltp>prevLtp?1:ltp<prevLtp?-1:lastSign;
                double signedVol=(double)sign*dv;
                boolean priceFlat = prevLtp>0 && Math.abs(ltp-prevLtp)/prevLtp*100.0 <= ABSORB_FLAT_PCT;
                boolean absorbAgainst = Math.abs(signedVol)>=absorbBar(idx) && sign>0 && priceFlat; // buy-flow absorbed

                // update windows
                win.addLast(new double[]{ts,dv,ltp});
                while(!win.isEmpty() && win.peekFirst()[0] < ts-THR_WIN) win.pollFirst();

                // SIGNAL (computed EVERY tick so the double-tick streak can be tracked)
                boolean histOk = win.peekFirst()!=null && win.peekFirst()[0] <= ts-50;
                boolean volSurge=false, thrust=false, depthConfirm=false;
                if(histOk){
                    double vol30=0, volPrior30=0; double ltp60=win.peekFirst()[2];
                    for(double[] w: win){ if(w[0]>ts-VOL_WIN) vol30+=w[1]; else volPrior30+=w[1]; }
                    volSurge = volPrior30>=MIN_VOLUME && volPrior30>0 && (vol30/volPrior30)>=VOL_RATIO_MIN;
                    thrust = ltp60>0 && (ltp-ltp60)/ltp60*100.0 >= THRUST_PCT_MIN;
                    double imb = (bq+aq)>0 ? (double)(bq-aq)/(bq+aq) : 0;
                    depthConfirm = imb>=IMBALANCE_MIN && signedVol>0;
                }
                boolean fireable = volSurge && thrust && depthConfirm && !absorbAgainst;
                fireStreak = fireable ? fireStreak+1 : 0;

                // EXIT
                if(inLong){
                    peak = Math.max(peak, ltp);
                    long held=ts-entryTs; boolean doExit=false; String rsn="";
                    if(MODE.equals("v3")){
                        double profitPct=(ltp-entryAsk)/entryAsk*100.0, ddFromPeak=(peak-ltp)/peak*100.0, peakGain=(peak-entryAsk)/entryAsk*100.0;
                        if(held>=MIN_HOLD_SEC && profitPct<=SL_PCT){ doExit=true; rsn="STOP_LOSS"; }
                        else if(held>=MIN_HOLD_SEC && peakGain>=TRAIL_ACTIVATE_PCT && ddFromPeak>=TRAIL_GAP_PCT){ doExit=true; rsn="TRAIL"; }
                        else if(held>=MAX_HOLD_SEC){ doExit=true; rsn="MAX_HOLD"; }
                    } else if(held>=MIN_HOLD_SEC && (absorbAgainst || held>=MAX_HOLD_SEC)){
                        doExit=true; rsn=absorbAgainst?"COE_REVERSAL":"MAX_HOLD";
                    }
                    if(doExit){
                        double exitBid = bid>0?bid:ltp;
                        trades.add(mk(dt,idx,type,strike,entryTs,ts,entryAsk,exitBid, rsn, lotSize(idx)));
                        inLong=false; cooldownUntil=ts+COOLDOWN_SEC; lastExitBid=exitBid;
                    }
                }
                // ENTRY (COE confirm) — only when off cooldown, real ask, enough history
                if(!inLong && ts>=cooldownUntil && ask>0 && histOk){
                    boolean fakeMove = absorbAgainst;
                    boolean ctoOk, confirmOk=true;
                    if(MODE.equals("v3")){ ctoOk = entriesToday < MAX_ENTRIES; confirmOk = fireStreak >= CONFIRM_TICKS; }
                    else if(MODE.equals("coe")) ctoOk = true;
                    // CTO structural gate: cap entries at (1 base + CTO_MAX_ADDS)/strike/day; any RE-entry at/above last exit.
                    else ctoOk = entriesThisGroup <= CTO_MAX_ADDS && (entriesThisGroup==0 || ask >= lastExitBid);
                    if(volSurge && thrust && depthConfirm && !fakeMove && confirmOk && ctoOk){
                        inLong=true; entryAsk=ask; entryTs=ts; peak=ask; entriesThisGroup++; entriesToday++;
                    }
                }
                prevCumVol=cum; prevLtp=ltp; lastSign=sign; lastBid=bid; lastLtp=ltp; lastTs=ts;
            }
            if(inLong){ trades.add(mk(cdt,cidx,ctype,cstrike,entryTs,lastTs,entryAsk,lastBid>0?lastBid:lastLtp,"EOD",lotSize(cidx))); }
        }
        report(trades);
    }

    static Trade mk(String dt,String idx,String type,int strike,long ets,long xts,double ea,double xb,String rsn,int lot){
        Trade t=new Trade(); t.dt=dt;t.idx=idx;t.type=type;t.strike=strike;t.entryTs=ets;t.exitTs=xts;t.entryAsk=ea;t.exitBid=xb;t.reason=rsn;t.lot=lot; return t;
    }

    static void report(List<Trade> ts){
        String modeLabel = MODE.equals("cto")?"CTO+COE" : MODE.equals("v3")?"v3 (cap"+MAX_ENTRIES+"/strike/day + "+CONFIRM_TICKS+"-tick confirm + SL/trail exit)" : "COE-alone";
        System.out.println("== "+modeLabel+" backtest [mode="+MODE
            +(MODE.equals("cto")?", max "+(1+CTO_MAX_ADDS)+" entries/strike/day, re-entry above last exit":"")
            +"] (spoof EXCLUDED, L1 imbalance proxy, buy-ask/sell-bid fills, -Rs"+(int)ROUND_TRIP_COST_RS+"/rt) ==");
        // overall
        agg("ALL TRADES", ts);
        System.out.println("\n-- by exit reason --");
        for(String rsn: new String[]{"COE_REVERSAL","STOP_LOSS","TRAIL","MAX_HOLD","EOD"}) agg("  "+rsn, filt(ts, t->t.reason.equals(rsn)));
        System.out.println("\n-- by index --");
        for(String idx: new String[]{"NIFTY","SENSEX","BANKNIFTY"}) agg("  "+idx, filt(ts, t->t.idx.equals(idx)));
        System.out.println("\n-- NIFTY expiry(0DTE 06-23/06-30) vs non-expiry --");
        agg("  NIFTY 0DTE", filt(ts, t->t.idx.equals("NIFTY")&&(t.dt.equals("06-23")||t.dt.equals("06-30"))));
        agg("  NIFTY non-exp", filt(ts, t->t.idx.equals("NIFTY")&&!(t.dt.equals("06-23")||t.dt.equals("06-30"))));
        System.out.println("\n-- trades PER TRADING DATE (all indices combined) --");
        java.util.Map<String,Integer> pd = new java.util.TreeMap<>();
        for(Trade t:ts) pd.merge(t.dt, 1, Integer::sum);
        for(var e: pd.entrySet()) System.out.printf("   %-8s %4d%n", e.getKey(), e.getValue());
        if(!pd.isEmpty()) System.out.printf("   => %d trades over %d dates = %.0f/day avg (all indices)%n",
                ts.size(), pd.size(), (double)ts.size()/pd.size());
    }
    static List<Trade> filt(List<Trade> ts, java.util.function.Predicate<Trade> p){ List<Trade> o=new ArrayList<>(); for(Trade t:ts) if(p.test(t)) o.add(t); return o; }
    static void agg(String label, List<Trade> ts){
        if(ts.isEmpty()){ System.out.printf("   %-16s n=0%n",label); return; }
        double gross=0,net=0; int win=0; long holdSum=0;
        for(Trade t:ts){ double g=(t.exitBid-t.entryAsk)*t.lot; double nn=g-ROUND_TRIP_COST_RS; gross+=g; net+=nn; if(nn>0)win++; holdSum+=(t.exitTs-t.entryTs); }
        System.out.printf("   %-16s n=%-5d  win%%=%-5.1f  grossRs=%-10.0f netRs=%-10.0f  avgNet=%-7.1f  avgHold=%ds%n",
            label, ts.size(), 100.0*win/ts.size(), gross, net, net/ts.size(), holdSum/ts.size());
    }
}
