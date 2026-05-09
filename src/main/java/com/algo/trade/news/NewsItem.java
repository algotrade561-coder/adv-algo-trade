package com.algo.trade.news;

import java.time.LocalDateTime;

/** A classified news item with sentiment and impact. */
public record NewsItem(
        String title,
        String summary,
        String source,
        String url,
        LocalDateTime publishedAt,
        Sentiment sentiment,
        Impact impact,
        Category category,
        double sentimentScore,
        boolean eventAlert
) {
    public boolean isHighImpact() { return impact == Impact.HIGH; }

    public enum Sentiment { BULLISH, BEARISH, NEUTRAL }
    public enum Impact { HIGH, MEDIUM, LOW }
    public enum Category { RBI, BUDGET, EARNINGS, GLOBAL, FII_DII, GENERAL }
}
