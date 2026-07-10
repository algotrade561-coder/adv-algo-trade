import java.nio.file.*; import java.util.*;
/** Cluster per-strike swings (reports/three-day-move-breakdown.md) into distinct index WAVES. */
public class WaveCluster {
    record Row(String day,String idx,String type,int strike,int tLow,int tHigh,double gain,String sig,int lead,String regime,String gate,String bot){}
    public static void main(String[] a) throws Exception {
        List<Row> rows = new ArrayList<>();
        for (String l : Files.readAllLines(Path.of("reports","three-day-move-breakdown.md"))) {
            if (!l.startsWith("| 07-")) continue;
            String[] f = l.split("[|]");
            // f[1]=day f[2]=inst f[3]=times f[4]=prices f[5]=gain f[6]=mins f[7]=signal f[8]=trig f[9]=regime f[10]=gate f[11]=bot
            String[] inst = f[2].trim().split(" ");
            String[] tt = f[3].trim().split("->");
            int tl = hm(tt[0]), th = hm(tt[1]);
            double g = Double.parseDouble(f[5].trim().replace("%",""));
            String sigf = f[7].trim(); String sig = sigf.contains("(")? sigf.substring(0,sigf.indexOf('(')) : sigf;
            int lead = sigf.contains("(") && !sigf.contains("(-") ? (int)Double.parseDouble(sigf.replaceAll(".*[(]","").replaceAll("s[)]","")) : -1;
            rows.add(new Row(f[1].trim(), inst[0], inst[2], Integer.parseInt(inst[1]), tl, th, g, sig, lead, f[9].trim(), f[10].trim(), f[11].trim()));
        }
        rows.sort(Comparator.comparing((Row r)->r.day).thenComparing(r->r.idx).thenComparing(r->r.type).thenComparing(r->r.tLow));
        System.out.println("day   | index-side   | wave window (IST) | strikes | medGain% | maxGain% | earliest-signal(maxLead) | regimes | bot: caught/late/missed | top blockers");
        String ck=null; List<Row> cl=new ArrayList<>(); List<String> out=new ArrayList<>();
        for (Row r : rows) {
            String k = r.day+"|"+r.idx+"|"+r.type;
            if (!k.equals(ck) || (cl.size()>0 && r.tLow > cl.get(cl.size()-1).tLow + 12*60)) { emit(cl,out); cl=new ArrayList<>(); ck=k; }
            cl.add(r);
        }
        emit(cl,out);
        for (String s : out) System.out.println(s);
    }
    static void emit(List<Row> cl, List<String> out) {
        if (cl.isEmpty()) return;
        Row f0 = cl.get(0);
        int tl = cl.stream().mapToInt(r->r.tLow).min().getAsInt(), th = cl.stream().mapToInt(r->r.tHigh).max().getAsInt();
        double[] gains = cl.stream().mapToDouble(r->r.gain).sorted().toArray();
        double med = gains[gains.length/2], mx = gains[gains.length-1];
        long caught = cl.stream().filter(r->r.bot.startsWith("CAUGHT")).count();
        long late = cl.stream().filter(r->r.bot.startsWith("LATE")).count();
        long missed = cl.size()-caught-late;
        Map<String,Integer> block=new HashMap<>(), sigs=new HashMap<>(), regs=new HashMap<>();
        int maxLead=-1; 
        for (Row r: cl){ if(r.lead>maxLead) maxLead=r.lead;
            sigs.merge(r.sig,1,Integer::sum); regs.merge(r.regime,1,Integer::sum);
            if(r.gate.startsWith("FAIL")) for(String b: r.gate.substring(5).split("[+]")) block.merge(b,1,Integer::sum); }
        String topBlock = block.entrySet().stream().sorted((x,y)->y.getValue()-x.getValue()).limit(3)
            .map(e->e.getKey()+"x"+e.getValue()).reduce((x,y)->x+","+y).orElse("-");
        String topSig = sigs.entrySet().stream().max(Comparator.comparingInt(Map.Entry::getValue)).map(Map.Entry::getKey).orElse("-");
        String topReg = regs.entrySet().stream().max(Comparator.comparingInt(Map.Entry::getValue)).map(Map.Entry::getKey).orElse("-");
        out.add(String.format("%s | %-7s %-3s | %s->%s | %7d | %8.0f | %8.0f | %s(%ds) | %s | %d/%d/%d | %s",
            f0.day, f0.idx, f0.type, t(tl), t(th), cl.size(), med, mx, topSig, maxLead, topReg, caught, late, missed, topBlock));
    }
    static int hm(String s){ String[] p=s.trim().split(":"); return Integer.parseInt(p[0])*3600+Integer.parseInt(p[1])*60; }
    static String t(int s){ return String.format("%02d:%02d", s/3600, (s%3600)/60); }
}
