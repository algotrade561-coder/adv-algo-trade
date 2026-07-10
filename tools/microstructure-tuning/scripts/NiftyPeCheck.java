import java.sql.*;
import java.util.*;

/**
 * Focused replay of NIFTY 24300 PE: did the CTO+COE backtest trade the afternoon move, and what were
 * the signal values vs thresholds? Prints (a) every trade on this instrument with the entry-signal
 * values, and (b) the afternoon 5-min trajectory (ltp + net flow + thrust + imbalance + fire flags).
 * Same logic/thresholds as CoeBacktest (cto mode). args: archive, csv, [strike=24300], [day=07-03]
 */
public class NiftyPeCheck {
    static final int VOL_WIN=30, THR_WIN=60; static final double VOL_RATIO_MIN=1.5, THRUST_PCT_MIN=2.0; static final long MIN_VOLUME=500;
    static final double IMBALANCE_MIN=0.25, ABSORB_FLAT_PCT=0.2, ABSORB_BAR=15000; // NIFTY
    static final long MIN_HOLD_SEC=3, COOLDOWN_SEC=60, MAX_HOLD_SEC=1800; static final int MAX_ADDS=1;
    static final int LOT=65; static final double COST=50;
    // v2 fixes: trend-riding exit (SL + trailing) instead of hair-trigger absorption; budget refunds on a losing scratch.
    static final double SL_PCT=-12, TRAIL_ACTIVATE_PCT=4, TRAIL_GAP_PCT=8;
    // v3: user-requested run — cap entries/strike/day + double-tick (N consecutive fireable ticks) entry confirmation.
    static final int MAX_ENTRIES=5, CONFIRM_TICKS=2;
    static String MODE="cto";

    public static void main(String[] a) throws Exception {
        String ARC=a[0], CSV=a[1]; int STRIKE=a.length>2?Integer.parseInt(a[2]):24300; String DAY=a.length>3?a[3]:"07-03";
        if(a.length>4) MODE=a[4];
        Class.forName("org.duckdb.DuckDBDriver");
        try(Connection c=DriverManager.getConnection("jdbc:duckdb:");Statement s=c.createStatement()){
            String sql="SELECT dt, ts, ltp, bestBid, bestAsk, bidQty, askQty, cumVolume FROM ("
              +" SELECT month||'-'||day dt, exchangeTsEpochSec ts, ltp, bestBid, bestAsk, bidQty, askQty, cumVolume"
              +" FROM read_parquet('"+ARC+"/**/*.parquet', hive_partitioning=1) WHERE \"index\"='NIFTY' AND strike="+STRIKE+" AND optionType='PE' AND ltp>0"
              +" UNION ALL SELECT '07-03' dt, exchangeTsEpochSec ts, ltp, bestBid, bestAsk, bidQty, askQty, cumVolume"
              +" FROM read_csv_auto('"+CSV+"') WHERE index='NIFTY' AND strike="+STRIKE+" AND optionType='PE' AND ltp>0"
              +") ORDER BY dt, ts";
            ResultSet r=s.executeQuery(sql);

            String cur=null; ArrayDeque<double[]> win=new ArrayDeque<>(); long prevCum=-1; double prevLtp=0; int lastSign=0;
            boolean inLong=false; double entryAsk=0; long entryTs=0,cooldownUntil=0; int adds=0; double lastExitBid=0; double peak=0;
            int fireStreak=0, entriesToday=0; // v3: consecutive-fireable counter + per-strike/day entry count (not refunded)
            // per-day trade + afternoon-trajectory accumulators
            Map<String,int[]> tradesPerDay=new TreeMap<>();
            List<String> log=new ArrayList<>();
            // afternoon buckets for the target day: key=HH:MM(5min) -> [ltpLast, netSV, maxThr(x1000), anyVolSurge, sumImb, nImb, absorbTk, entryTk]
            Map<Integer,double[]> aft=new TreeMap<>();

            while(r.next()){
                String dt=r.getString(1); long ts=r.getLong(2); double ltp=r.getDouble(3),bid=r.getDouble(4),ask=r.getDouble(5);
                long bq=r.getLong(6),aq=r.getLong(7),cum=r.getLong(8);
                if(!dt.equals(cur)){ cur=dt; win.clear(); prevCum=-1; prevLtp=0; lastSign=0; inLong=false; adds=0; cooldownUntil=0; fireStreak=0; entriesToday=0; }
                long dv=(prevCum<0||cum<prevCum)?0:(cum-prevCum);
                int sign; if(bid>0&&ask>0){ if(ltp>=ask)sign=1; else if(ltp<=bid)sign=-1; else sign=ltp>prevLtp?1:ltp<prevLtp?-1:lastSign; } else sign=ltp>prevLtp?1:ltp<prevLtp?-1:lastSign;
                double sv=(double)sign*dv;
                boolean flat=prevLtp>0 && Math.abs(ltp-prevLtp)/prevLtp*100<=ABSORB_FLAT_PCT;
                boolean absorb=Math.abs(sv)>=ABSORB_BAR && sign>0 && flat;
                win.addLast(new double[]{ts,dv,ltp}); while(!win.isEmpty()&&win.peekFirst()[0]<ts-THR_WIN) win.pollFirst();
                double vol30=0,volP30=0,ltp60=win.peekFirst()!=null?win.peekFirst()[2]:0;
                for(double[] w:win){ if(w[0]>ts-VOL_WIN) vol30+=w[1]; else volP30+=w[1]; }
                boolean volSurge=volP30>=MIN_VOLUME&&volP30>0&&(vol30/volP30)>=VOL_RATIO_MIN;
                double thr=ltp60>0?(ltp-ltp60)/ltp60*100:0; boolean thrust=thr>=THRUST_PCT_MIN;
                double imb=(bq+aq)>0?(double)(bq-aq)/(bq+aq):0;
                boolean depthConfirm=imb>=IMBALANCE_MIN && sv>0; boolean fireable=volSurge&&thrust&&depthConfirm&&!absorb;
                fireStreak = fireable ? fireStreak+1 : 0; // v3: consecutive fireable ticks (this tick included)

                // afternoon trajectory for target day (IST hour>=12)
                long ist=ts+330*60; int istH=(int)((ist/3600)%24), istM=(int)((ist%3600)/60);
                if(dt.equals(DAY) && istH>=12 && istH<=15){
                    int bk=istH*60+ (istM/5)*5;
                    double[] b=aft.computeIfAbsent(bk,k->new double[8]);
                    b[0]=ltp; b[1]+=sv; if(thr>b[2])b[2]=thr; if(volSurge)b[3]=1; b[4]+=imb; b[5]++; if(absorb)b[6]++; if(fireable)b[7]++;
                }
                // EXIT
                if(inLong){
                    peak=Math.max(peak, ltp);
                    long held=ts-entryTs; boolean doExit=false; String rsn="";
                    if(MODE.equals("v2")||MODE.equals("v3")){
                        double profitPct=(ltp-entryAsk)/entryAsk*100, ddFromPeak=(peak-ltp)/peak*100, peakGain=(peak-entryAsk)/entryAsk*100;
                        if(held>=MIN_HOLD_SEC && profitPct<=SL_PCT){ doExit=true; rsn="STOP_LOSS"; }
                        else if(held>=MIN_HOLD_SEC && peakGain>=TRAIL_ACTIVATE_PCT && ddFromPeak>=TRAIL_GAP_PCT){ doExit=true; rsn="TRAIL"; }
                        else if(held>=MAX_HOLD_SEC){ doExit=true; rsn="MAX_HOLD"; }
                    } else {
                        if(held>=MIN_HOLD_SEC && (absorb||held>=MAX_HOLD_SEC)){ doExit=true; rsn=absorb?"COE_REVERSAL":"MAX_HOLD"; }
                    }
                    if(doExit){
                        double xb=bid>0?bid:ltp; double pnl=(xb-entryAsk)*LOT-COST;
                        log.add(String.format("  %s EXIT  %02d:%02d ist  bid=%.1f  reason=%-11s pnl=Rs%.0f", dt, istH,istM, xb, rsn, pnl));
                        tradesPerDay.computeIfAbsent(dt,k->new int[1])[0]++;
                        inLong=false; cooldownUntil=ts+COOLDOWN_SEC; lastExitBid=xb;
                        if(MODE.equals("v2")) adds=Math.max(0,adds-1); // concurrent model: a completed round-trip frees the slot (win OR loss)
                    }
                }
                // ENTRY
                if(!inLong && ts>=cooldownUntil && ask>0 && win.peekFirst()!=null && win.peekFirst()[0]<=ts-50){
                    boolean ctoOk; boolean confirmOk=true;
                    if(MODE.equals("v3")){ ctoOk = entriesToday < MAX_ENTRIES; confirmOk = fireStreak >= CONFIRM_TICKS; }
                    else if(MODE.equals("v2")) ctoOk = adds<=MAX_ADDS;
                    else ctoOk = adds<=MAX_ADDS && (adds==0 || ask>=lastExitBid);
                    if(fireable && confirmOk && ctoOk){ inLong=true; entryAsk=ask; entryTs=ts; peak=ask; adds++; entriesToday++;
                        log.add(String.format("  %s ENTRY %02d:%02d ist  ask=%.1f  volRatio=%.2f thrust=%.1f%% imb=%.2f signedVol=%.0f streak=%d entry#%d (bar=%.0f)",
                            dt, istH,istM, ask, volP30>0?vol30/volP30:0, thr, imb, sv, fireStreak, entriesToday, ABSORB_BAR)); }
                }
                prevCum=cum; prevLtp=ltp; lastSign=sign;
            }
            System.out.println("== NIFTY "+STRIKE+" PE — CTO+COE backtest trades (all captured days) ==");
            if(log.isEmpty()) System.out.println("   (no trades — entry gate never fired on this strike)");
            for(String l:log) System.out.println(l);
            System.out.println("\n   trades/day: "+tradesPerDay.entrySet().stream().map(e->e.getKey()+"="+e.getValue()[0]).reduce((x,y)->x+", "+y).orElse("none"));

            System.out.println("\n== "+DAY+" afternoon 5-min trajectory (ltp / netFlow / maxThrust / anyVolSurge / avgImb / absorbTk / entryFireableTk) ==");
            System.out.printf("   %-7s %8s %10s %8s %6s %6s %7s %8s%n","IST","ltp","netFlow","maxThr%","surge","avgImb","absorbTk","fireTk");
            for(var e:aft.entrySet()){ double[] b=e.getValue(); int h=e.getKey()/60,m=e.getKey()%60;
                System.out.printf("   %02d:%02d   %8.1f %10.0f %8.1f %6s %6.2f %7.0f %8.0f%n", h,m, b[0], b[1], b[2], b[3]==1?"Y":"-", b[5]>0?b[4]/b[5]:0, b[6], b[7]); }
        }
    }
}
