import java.sql.*;

/**
 * Full-history Conviction-Override threshold tuning — run LOCALLY (not on the 2GB prod box).
 * args[0] = archive dir (hive-partitioned parquet), args[1] = 07-03 csv path.
 */
public class MicroTune {
  public static void main(String[] a) throws Exception {
    String ARC=a[0], CSV=a[1];
    Class.forName("org.duckdb.DuckDBDriver");
    try(Connection c=DriverManager.getConnection("jdbc:duckdb:");Statement s=c.createStatement()){
      // Unified per-tick ΔV + L1 imbalance across the whole history (archive 06-20..07-02 + 07-03 csv).
      s.execute("CREATE TABLE dv AS "
        +"SELECT dt, idx, GREATEST(cumVolume-LAG(cumVolume) OVER (PARTITION BY dt,idx,strike,optionType ORDER BY ts),0) dv, "
        +"CASE WHEN (bidQty+askQty)>0 THEN (bidQty-askQty)/CAST(bidQty+askQty AS DOUBLE) ELSE 0 END imb FROM ("
        +"  SELECT month||'-'||day dt, \"index\" idx, strike, optionType, exchangeTsEpochSec ts, ltp, bidQty, askQty, cumVolume "
        +"  FROM read_parquet('"+ARC+"/**/*.parquet', hive_partitioning=1) WHERE ltp>0 "
        +"  UNION ALL "
        +"  SELECT '07-03' dt, index idx, strike, optionType, exchangeTsEpochSec ts, ltp, bidQty, askQty, cumVolume "
        +"  FROM read_csv_auto('"+CSV+"') WHERE ltp>0)");

      System.out.println("== per day/index: ticks, ΔV p90/p99 (absorption bar), imbalance p75 ==");
      System.out.println("   (NIFTY 0DTE expiry: 06-23, 06-30 Tue)");
      ResultSet r=s.executeQuery("SELECT dt, idx, COUNT(*) ticks, ROUND(quantile_cont(dv,0.9)) dvp90, "
        +"ROUND(quantile_cont(dv,0.99)) dvp99, ROUND(quantile_cont(imb,0.75),3) imbp75 FROM dv WHERE dv>0 GROUP BY dt,idx ORDER BY idx,dt");
      System.out.printf("   %-8s %-9s %10s %9s %9s %8s%n","date","index","ticks","dvP90","dvP99","imbP75");
      while(r.next()){ String dt=r.getString("dt"),idx=r.getString("idx");
        String e=(dt.equals("06-23")||dt.equals("06-30"))&&idx.equals("NIFTY")?"  <=0DTE":"";
        System.out.printf("   %-8s %-9s %10d %9s %9s %8s%s%n",dt,idx,r.getLong("ticks"),r.getString("dvp90"),r.getString("dvp99"),r.getString("imbp75"),e);}

      System.out.println("\n== robust per-index absorption bar: median/min/max of daily ΔV-p90 (trading days, ticks>50k) ==");
      ResultSet r2=s.executeQuery("WITH pd AS (SELECT dt,idx,COUNT(*) n,quantile_cont(dv,0.9) p90 FROM dv WHERE dv>0 GROUP BY dt,idx) "
        +"SELECT idx, COUNT(*) ndays, ROUND(median(p90)) medP90, ROUND(MIN(p90)) minP90, ROUND(MAX(p90)) maxP90 FROM pd WHERE n>50000 GROUP BY idx ORDER BY idx");
      System.out.printf("   %-9s %6s %9s %9s %9s%n","index","days","medP90","minP90","maxP90");
      while(r2.next()) System.out.printf("   %-9s %6d %9s %9s %9s%n",r2.getString("idx"),r2.getLong("ndays"),r2.getString("medP90"),r2.getString("minP90"),r2.getString("maxP90"));

      System.out.println("\n== expiry vs non-expiry (NIFTY): does the absorption bar spike on 0DTE? ==");
      ResultSet r3=s.executeQuery("WITH pd AS (SELECT dt,COUNT(*) n,quantile_cont(dv,0.9) p90 FROM dv WHERE idx='NIFTY' AND dv>0 GROUP BY dt) "
        +"SELECT CASE WHEN dt IN ('06-23','06-30') THEN 'EXPIRY(0DTE)' ELSE 'non-expiry' END grp, COUNT(*) ndays, ROUND(median(p90)) medP90 "
        +"FROM pd WHERE n>50000 GROUP BY grp ORDER BY grp");
      System.out.printf("   %-14s %6s %9s%n","group","days","medP90");
      while(r3.next()) System.out.printf("   %-14s %6d %9s%n",r3.getString("grp"),r3.getLong("ndays"),r3.getString("medP90"));
    }
  }
}
