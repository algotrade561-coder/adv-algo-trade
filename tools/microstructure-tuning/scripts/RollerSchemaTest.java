import java.sql.*;
import java.nio.file.*;
import java.io.BufferedWriter;
import java.util.*;
import com.algo.trade.marketdata.DepthSnapshot;
import com.algo.trade.marketdata.ConvictionOverrideEngine.OverrideSnapshot;

/**
 * Post-deploy safety test: proves the CSV→Parquet roller (MicrostructureParquetRoller) handles the
 * new wide schema (spoofActive + 32 raw-ladder columns) without breaking. Builds sample rows with the
 * REAL DepthSnapshot/OverrideSnapshot records (same format the recorder writes), runs the roller's
 * EXACT COPY SQL, and verifies the round-trip. Run locally; touches nothing on prod.
 * Usage: java -cp "target/classes;<duckdb.jar>" RollerSchemaTest.java
 */
public class RollerSchemaTest {
    static final String HEADER =
        "recvEpochMs,exchangeTsEpochSec,index,strike,optionType,tradingSymbol,ltp,"
      + "bestBid,bestAsk,bidQty,askQty,cumVolume,oi,"
      + "bookImbalance,bestBidOrders,bestAskOrders,bidSpoofScore,askSpoofScore,"
      + "signedVolume,aggressor,isAbsorption,spoofActive,"
      + "bpx1,bq1,bo1,bpx2,bq2,bo2,bpx3,bq3,bo3,bpx4,bq4,bo4,bpx5,bq5,bo5,"
      + "apx1,aq1,ao1,apx2,aq2,ao2,apx3,aq3,ao3,apx4,aq4,ao4,apx5,aq5,ao5,"
      + "totalBuyQty,totalSellQty";

    // Copied verbatim from AtmMicrostructureRecorder.ladderCsv(...)
    static String ladderCsv(DepthSnapshot d) {
        StringBuilder sb = new StringBuilder(160);
        for (int i = 0; i < 5; i++) sb.append(d.bidPrices()[i]).append(',').append(d.bidQtys()[i]).append(',').append(d.bidOrders()[i]).append(',');
        for (int i = 0; i < 5; i++) sb.append(d.askPrices()[i]).append(',').append(d.askQtys()[i]).append(',').append(d.askOrders()[i]).append(',');
        sb.append(d.totalBuyQty()).append(',').append(d.totalSellQty());
        return sb.toString();
    }
    // Copied verbatim from AtmMicrostructureRecorder.onOptionTick row build
    static String row(long recv,int exchTs,String idx,int strike,String type,String sym,double ltp,double bb,double ba,long bq,long aq,long cum,long oi,DepthSnapshot d,OverrideSnapshot os){
        String base = recv+","+exchTs+","+idx+","+strike+","+type+","+sym+","+ltp+","+bb+","+ba+","+bq+","+aq+","+cum+","+oi+",";
        String derived = d!=null ? String.format(Locale.US,"%.4f,%d,%d,%.3f,%.3f,", d.bookImbalance(), d.bidOrders()[0], d.askOrders()[0], d.bidSpoofScores()[0], d.askSpoofScores()[0]) : "0,0,0,0,0,";
        String flow = os!=null ? os.signedVolume()+","+os.aggressor()+","+os.absorption()+","+os.spoofActive()+"," : "0,0,false,false,";
        String ladder = d!=null ? ladderCsv(d) : "0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0";
        return base+derived+flow+ladder;
    }

    public static void main(String[] a) throws Exception {
        long[] bq={5000,3000,2000,1000,500}; double[] bp={100.0,99.95,99.90,99.85,99.80}; int[] bo={1,10,5,8,3};
        long[] aq={4000,2500,1500,900,400};  double[] ap={100.05,100.10,100.15,100.20,100.25}; int[] ao={2,12,6,7,4};
        DepthSnapshot d = new DepthSnapshot(bq,bp,bo,aq,ap,ao, 200000, 180000);
        OverrideSnapshot os    = new OverrideSnapshot(1720000000000L, d.bookImbalance(), 1, 15000L, true,  false);
        OverrideSnapshot osNeg = new OverrideSnapshot(1720000000000L, -0.3, -1, -8000L, false, true);

        List<String> rows = new ArrayList<>();
        rows.add(row(1720000000000L,1720000000,"NIFTY",24350,"CE","NIFTY24350CE",100.0,100.0,100.05,5000,4000,123456,7890,d,os));
        rows.add(row(1720000001000L,1720000001,"SENSEX",78000,"PE","SENSEX78000PE",250.5,250.0,251.0,3000,2000,55000,4000,d,osNeg));
        rows.add(row(1720000002000L,1720000002,"NIFTY",24300,"PE","NIFTY24300PE",90.0,89.9,90.1,1000,1200,10000,3000,null,null)); // fallback (no depth/COE)

        int hdr = HEADER.split(",",-1).length;
        System.out.println("header columns = "+hdr);
        boolean countsOk=true;
        for(int i=0;i<rows.size();i++){ int rc=rows.get(i).split(",",-1).length; boolean ok=rc==hdr; countsOk&=ok; System.out.println("  row "+i+" fields="+rc+(ok?" OK":" *** MISMATCH")); }

        Path tmp=Files.createTempDirectory("rollertest");
        Path csv=tmp.resolve("atm-microstructure-2026-07-07.csv");
        Path dayDir=tmp.resolve("archive-day"); Files.createDirectories(dayDir);
        try(BufferedWriter w=Files.newBufferedWriter(csv)){ w.write(HEADER+"\n"); for(String r:rows){ w.write(r); w.write("\n"); } }

        Class.forName("org.duckdb.DuckDBDriver");
        String csvP=csv.toAbsolutePath().toString().replace("\\","/");
        String dirP=dayDir.toAbsolutePath().toString().replace("\\","/");
        try(Connection c=DriverManager.getConnection("jdbc:duckdb:");Statement s=c.createStatement()){
            String sql="COPY (SELECT * FROM read_csv_auto('"+csvP+"', header=true)) TO '"+dirP+"' (FORMAT 'parquet', COMPRESSION 'zstd', PARTITION_BY (index), OVERWRITE_OR_IGNORE)";
            System.out.println("\n[roller COPY] "+sql.substring(0,60)+"...");
            s.execute(sql);
            String glob=dirP+"/**/*.parquet";
            ResultSet r=s.executeQuery("SELECT COUNT(*) n FROM read_parquet('"+glob+"')"); r.next(); long pq=r.getLong(1);
            System.out.println("row-count verify: csv=3 parquet="+pq+" -> "+(pq==3?"MATCH":"*** MISMATCH"));
            ResultSet dd=s.executeQuery("DESCRIBE SELECT * FROM read_parquet('"+glob+"', hive_partitioning=1)");
            int cols=0; Set<String> names=new HashSet<>(); while(dd.next()){cols++;names.add(dd.getString(1));}
            System.out.println("parquet columns="+cols+"  spoofActive="+names.contains("spoofActive")+" bq1="+names.contains("bq1")+" totalSellQty="+names.contains("totalSellQty")+" index(part)="+names.contains("index"));
            System.out.println("readback (new cols) by partition:");
            ResultSet r3=s.executeQuery("SELECT index, spoofActive, isAbsorption, bq1, apx5, totalSellQty FROM read_parquet('"+glob+"', hive_partitioning=1) ORDER BY exchangeTsEpochSec");
            while(r3.next()) System.out.println("   idx="+r3.getString(1)+" spoofActive="+r3.getBoolean(2)+" isAbsorption="+r3.getBoolean(3)+" bq1="+r3.getLong(4)+" apx5="+r3.getDouble(5)+" totalSellQty="+r3.getLong(6));
            boolean pass = countsOk && pq==3 && names.contains("spoofActive") && names.contains("bq1") && cols>=54;
            System.out.println("\n==== "+(pass?"PASS — roller handles the new schema cleanly":"FAIL")+" ====");
        }
    }
}
