import java.sql.*;
public class RowCheck { public static void main(String[] a) throws Exception {
  Class.forName("org.duckdb.DuckDBDriver");
  try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
    ResultSet r = s.executeQuery(
      "SELECT filename, tradingSymbol, count(*), min(ltp), max(ltp) FROM read_csv_auto(['data/atm-microstructure-2026-07-06.csv','data/atm-0707.csv','data/atm-microstructure-2026-07-08.csv'], union_by_name=true, filename=true) "
      + "WHERE strike=24500 AND optionType='PE' AND strftime(to_timestamp(exchangeTsEpochSec+19800),'%m-%d')='07-07' "
      + "AND (exchangeTsEpochSec+19800)%86400 BETWEEN 52800 AND 54600 GROUP BY 1,2");
    while (r.next()) System.out.println(r.getString(1)+" | "+r.getString(2)+"  n="+r.getLong(3)+"  ltp="+r.getDouble(4)+".."+r.getDouble(5));
  } } }
