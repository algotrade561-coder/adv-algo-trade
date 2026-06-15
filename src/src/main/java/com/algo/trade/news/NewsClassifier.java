package com.algo.trade.news;

import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Classifies news headlines into sentiment and impact categories.
 * Keyword-based — no ML dependency, works offline.
 */
@Component
public class NewsClassifier {

    private static final List<String> BULLISH = List.of(
        "rate cut", "repo cut", "stimulus", "gdp growth", "record high", "all-time high",
        "strong earnings", "beat estimates", "profit rises", "revenue growth", "fii buying",
        "foreign inflow", "upgrade", "buy rating", "positive outlook", "recovery",
        "inflation eases", "trade surplus", "rupee strengthens", "market rally",
        "rbi accommodative", "gst collection record", "pmi expansion");

    private static final List<String> BEARISH = List.of(
        "rate hike", "hawkish", "inflation rises", "recession", "gdp slowdown",
        "profit falls", "revenue decline", "miss estimates", "fii selling", "foreign outflow",
        "downgrade", "sell rating", "negative outlook", "rupee weakens", "market crash",
        "rbi tightening", "fiscal deficit", "trade deficit", "oil price surge",
        "us fed hike", "geopolitical tension", "war", "sanctions");

    private static final List<String> HIGH_IMPACT = List.of(
        "rbi policy", "rbi rate", "repo rate", "union budget", "election result",
        "fed meeting", "fomc", "us cpi", "us jobs", "gdp data", "sebi circular",
        "circuit breaker", "market halt", "nifty crash", "sensex crash");

    private static final List<String> EVENT = List.of(
        "rbi policy", "rbi rate decision", "union budget", "election result",
        "fed decision", "fomc decision", "quarterly results", "f&o expiry");

    public NewsItem classify(String title, String summary, String source, String url,
                              LocalDateTime publishedAt) {
        String text = (title + " " + summary).toLowerCase();
        long bull = BULLISH.stream().filter(text::contains).count();
        long bear = BEARISH.stream().filter(text::contains).count();
        long highImpact = HIGH_IMPACT.stream().filter(text::contains).count();
        boolean isEvent = EVENT.stream().anyMatch(text::contains);

        double total = bull + bear;
        double score = total > 0 ? (double)(bull - bear) / total : 0;

        NewsItem.Sentiment sentiment = score > 0.2 ? NewsItem.Sentiment.BULLISH
                : score < -0.2 ? NewsItem.Sentiment.BEARISH : NewsItem.Sentiment.NEUTRAL;
        NewsItem.Impact impact = (highImpact >= 2 || isEvent) ? NewsItem.Impact.HIGH
                : (highImpact >= 1 || total >= 3) ? NewsItem.Impact.MEDIUM : NewsItem.Impact.LOW;
        NewsItem.Category category = NewsItem.Category.GENERAL;
        if (text.contains("rbi") || text.contains("repo rate")) category = NewsItem.Category.RBI;
        else if (text.contains("budget") || text.contains("fiscal")) category = NewsItem.Category.BUDGET;
        else if (text.contains("earnings") || text.contains("quarterly")) category = NewsItem.Category.EARNINGS;
        else if (text.contains("fed") || text.contains("global") || text.contains("crude")) category = NewsItem.Category.GLOBAL;
        else if (text.contains("fii") || text.contains("dii")) category = NewsItem.Category.FII_DII;

        return new NewsItem(title, summary, source, url, publishedAt, sentiment, impact, category, score, isEvent);
    }
}
