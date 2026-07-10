import java.sql.*;
import java.util.*;
public class Case24500 { public static void main(String[] a) throws Exception {
  Class.forName("org.duckdb.DuckDBDriver");
  try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
    ResultSet r = s.executeQuery("SELECT exchangeTsEpochSec ts, ltp, oi, cumVolume, bookImbalance, signedVolume FROM read_csv_auto('data/atm-0707.csv') WHERE strike=24500 AND optionType='PE' AND exchangeTsEpochSec BETWEEN 1783411200 AND 1783418100 ORDER BY ts");
    List<double[]> rows = new ArrayList<>();
    while (r.next()) rows.add(new double[]{r.getLong(1), r.getDouble(2), r.getDouble(3), r.getLong(4), r.getDouble(5), r.getDouble(6)});
    System.out.println("rows=" + rows.size());
    double minP=1e9, maxP=0; long tMin=0, tMax=0;
    for (double[] x : rows) { if (x[1] < minP) { minP=x[1]; tMin=(long)x[0]; } if (x[1] > maxP) { maxP=x[1]; tMax=(long)x[0]; } }
    System.out.printf("window low=%.1f @%s   high=%.1f @%s%n", minP, ist(tMin), maxP, ist(tMax));
    long next = 0; int j5=0; double oiStart=rows.get(0)[2];
    ArrayDeque<double[]> low15 = new ArrayDeque<>();
    System.out.printf("%-9s %8s %10s %7s %8s %7s %7s %9s %6s %s%n","IST","ltp","OI","dOI5m%","dOIcum%","dP5m%","stage%","vol/1m","imb","state");
    for (int i=0;i<rows.size();i++){ double[] x=rows.get(i); long ts=(long)x[0];
      low15.addLast(new double[]{ts,x[1]}); while(!low15.isEmpty()&&low15.peekFirst()[0]<ts-900) low15.pollFirst();
      if (ts < next) continue; next = ts+60;
      while(j5<i && rows.get(j5)[0]<ts-300) j5++;
      double dOi5 = rows.get(j5)[2]>0 ? (x[2]-rows.get(j5)[2])/rows.get(j5)[2]*100 : 0;
      double dP5 = rows.get(j5)[1]>0 ? (x[1]-rows.get(j5)[1])/rows.get(j5)[1]*100 : 0;
      double dOiCum = oiStart>0 ? (x[2]-oiStart)/oiStart*100 : 0;
      double lo=1e9; for(double[] w: low15) lo=Math.min(lo,w[1]);
      double stage = lo>0? (x[1]-lo)/lo*100 : 0;
      double v1=0; for(int k=i;k>0&&rows.get(k)[0]>=ts-60;k--){ if(rows.get(k)[3]>=rows.get(k-1)[3]) v1+=rows.get(k)[3]-rows.get(k-1)[3]; }
      String st = dOi5<=-0.5&&dP5>=3?"SHORT-COVER-RUN": dOi5>=0.5&&dP5<=-3?"WRITER-PRESS": Math.abs(dOi5)<0.5&&Math.abs(dP5)<3?"DEAD":"NEUTRAL";
      System.out.printf("%-9s %8.1f %10.0f %7.2f %8.1f %7.1f %7.1f %9.0f %6.2f %s%n", ist(ts), x[1], x[2], dOi5, dOiCum, dP5, stage, v1, x[4], st);
    }
  } }
  static String ist(long ts){ long t=ts+19800; return String.format("%02d:%02d:%02d",(t/3600)%24,(t%3600)/60,t%60); }
}
