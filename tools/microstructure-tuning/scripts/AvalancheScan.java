import java.sql.*;
import java.util.*;
public class AvalancheScan {
  static final int TS=0, LTP=1, OI=2;
  public static void main(String[] a) throws Exception {
    Class.forName("org.duckdb.DuckDBDriver");
    List<String[]> events = new ArrayList<>();
    try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
      s.execute("PRAGMA threads=4"); s.execute("SET memory_limit='6GB'");
      String files = "['data/atm-microstructure-2026-07-06.csv','data/atm-0707.csv','data/atm-microstructure-2026-07-08.csv']";
      ResultSet r = s.executeQuery(
        "SELECT strftime(to_timestamp(exchangeTsEpochSec+19800),'%m-%d') dt, index idx, strike, optionType ty, exchangeTsEpochSec ts, ltp, oi "
        + "FROM read_csv_auto(" + files + ", union_by_name=true) WHERE ltp>0 AND exchangeTsEpochSec>1700000000 "
        + "AND (exchangeTsEpochSec+19800)%86400 BETWEEN 33300 AND 55800 ORDER BY dt, idx, strike, ty, ts");
      String curKey=null, dt=null, idx=null, ty=null; int strike=0;
      List<double[]> ser = new ArrayList<>(30000);
      while (true) {
        boolean has = r.next();
        String key = has ? r.getString(1)+"|"+r.getString(2)+"|"+r.getInt(3)+"|"+r.getString(4) : null;
        if (!has || !key.equals(curKey)) {
          if (curKey != null && ser.size() > 500) scan(dt, idx, strike, ty, ser, events);
          if (!has) break;
          curKey=key; dt=r.getString(1); idx=r.getString(2); strike=r.getInt(3); ty=r.getString(4);
          ser = new ArrayList<>(30000);
        }
        ser.add(new double[]{r.getLong(5), r.getDouble(6), r.getDouble(7)});
      }
    }
    System.out.println("== UNWIND-AVALANCHE events (dOI5m<=-5% after OI down >=8% from its 60m peak; dedupe 15min/inst) ==");
    System.out.printf("%-6s %-22s %-9s %8s %8s | %9s %9s %9s%n","day","inst","IST","ltp","dOI5m%","fwd15max%","fwd30max%","fwd30drop%");
    double sumG15=0, sumG30=0, sumD30=0; int nWin15=0;
    for (String[] e : events) { System.out.printf("%-6s %-22s %-9s %8s %8s | %9s %9s %9s%n",(Object[])e);
      sumG15+=Double.parseDouble(e[5]); sumG30+=Double.parseDouble(e[6]); sumD30+=Double.parseDouble(e[7]);
      if (Double.parseDouble(e[5])>=10) nWin15++; }
    int n=events.size();
    if (n>0) System.out.printf("%n=> %d events (%.1f/day). avg fwd15max=%.1f%%  fwd30max=%.1f%%  fwd30drop=%.1f%%  P(fwd15>=10%%)=%.0f%%%n",
      n, n/3.0, sumG15/n, sumG30/n, sumD30/n, 100.0*nWin15/n);
  }
  static void scan(String day, String idx, int strike, String ty, List<double[]> ser, List<String[]> out) {
    int n = ser.size(); long lastEvent = 0;
    int j5 = 0; ArrayDeque<double[]> peak60 = new ArrayDeque<>(); // {ts, oi}
    for (int i = 0; i < n; i++) {
      double[] p = ser.get(i); long ts = (long) p[TS];
      peak60.addLast(new double[]{ts, p[OI]});
      while (!peak60.isEmpty() && peak60.peekFirst()[0] < ts - 3600) peak60.pollFirst();
      while (j5 < i && ser.get(j5)[TS] < ts - 300) j5++;
      double oi5 = ser.get(j5)[OI];
      if (oi5 <= 0 || p[LTP] < 15 || p[LTP] > 700) continue;
      double dOi5 = (p[OI] - oi5) / oi5 * 100;
      double mx60 = 0; for (double[] w : peak60) mx60 = Math.max(mx60, w[1]);
      boolean fromBuild = mx60 > 0 && p[OI] <= mx60 * 0.92; // OI already down >=8% from its 60m peak
      long istSec = (ts + 19800) % 86400;
      double p5 = ser.get(j5)[LTP]; double dP5 = p5 > 0 ? (p[LTP] - p5) / p5 * 100 : 0;
      if (dOi5 <= -5 && fromBuild && dP5 >= 0 && istSec >= 34200 && istSec <= 54600 && ts - lastEvent >= 900) {
        lastEvent = ts;
        double mx15 = 0, mx30 = 0, dn30 = 0;
        for (int k = i + 1; k < n && ser.get(k)[TS] <= ts + 1800; k++) {
          double g = (ser.get(k)[LTP] - p[LTP]) / p[LTP] * 100;
          if (ser.get(k)[TS] <= ts + 900 && g > mx15) mx15 = g;
          if (g > mx30) mx30 = g; if (-g > dn30) dn30 = -g;
        }
        out.add(new String[]{day, idx + " " + strike + " " + ty, ist(ts), String.format("%.1f", p[LTP]),
            String.format("%.1f", dOi5), String.format("%.1f", mx15), String.format("%.1f", mx30), String.format("%.1f", dn30)});
      }
    }
  }
  static String ist(long ts){ long t=ts+19800; return String.format("%02d:%02d:%02d",(t/3600)%24,(t%3600)/60,t%60); }
}
