package com.algo.trade.news;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches financial news from free RSS feeds and classifies them.
 * Sources: Economic Times, MoneyControl, LiveMint (all free, no API key).
 * Refreshes every 5 minutes during market hours.
 */
@Service
public class NewsFeedService {

    private static final Logger log = LoggerFactory.getLogger(NewsFeedService.class);
    private final NewsClassifier classifier;
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build();
    private final CopyOnWriteArrayList<NewsItem> newsStore = new CopyOnWriteArrayList<>();
    private static final int MAX_ITEMS = 50;

    private static final Map<String, String> RSS_FEEDS = Map.of(
            "Economic Times", "https://economictimes.indiatimes.com/markets/rssfeeds/1977021501.cms",
            "MoneyControl", "https://www.moneycontrol.com/rss/marketreports.xml",
            "LiveMint", "https://www.livemint.com/rss/markets");

    public NewsFeedService(NewsClassifier classifier) {
        this.classifier = classifier;
    }

    @Scheduled(fixedDelay = 300_000)
    public void scheduledFetch() {
        if (!isMarketHours()) return;
        fetchAllFeeds();
    }

    private void fetchAllFeeds() {
        RSS_FEEDS.forEach((source, url) -> {
            try {
                List<NewsItem> items = fetchRss(url, source);
                for (NewsItem item : items) {
                    if (newsStore.stream().noneMatch(n -> n.title().equals(item.title()))) {
                        newsStore.add(0, item);
                        if (item.isHighImpact()) {
                            log.warn("[News] HIGH IMPACT: [{}] {} — {}", item.sentiment(), item.title(), source);
                        }
                    }
                }
                while (newsStore.size() > MAX_ITEMS) newsStore.remove(newsStore.size() - 1);
            } catch (Exception e) {
                log.debug("[News] Failed to fetch {}: {}", source, e.getMessage());
            }
        });
    }

    private List<NewsItem> fetchRss(String url, String source) throws Exception {
        Request request = new Request.Builder().url(url)
                .addHeader("User-Agent", "Mozilla/5.0 AlgoTrader/1.0").build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return List.of();
            return parseRss(response.body().string(), source);
        }
    }

    private List<NewsItem> parseRss(String xml, String source) {
        List<NewsItem> items = new ArrayList<>();
        Pattern itemP = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL);
        Pattern titleP = Pattern.compile("<title>(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</title>", Pattern.DOTALL);
        Pattern descP = Pattern.compile("<description>(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</description>", Pattern.DOTALL);
        Matcher m = itemP.matcher(xml);
        while (m.find() && items.size() < 10) {
            String item = m.group(1);
            String title = extract(titleP, item, "No title").replaceAll("<[^>]+>", "").trim();
            String desc = extract(descP, item, "").replaceAll("<[^>]+>", "").trim();
            items.add(classifier.classify(title, desc, source, "", LocalDateTime.now()));
        }
        return items;
    }

    private String extract(Pattern p, String text, String def) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1).trim() : def;
    }

    // ── Public API ────────────────────────────────────────────────────────
    public List<NewsItem> getLatestNews() { return Collections.unmodifiableList(newsStore); }
    public List<NewsItem> getHighImpactNews() {
        return newsStore.stream().filter(NewsItem::isHighImpact).limit(10).toList();
    }
    public double getMarketSentimentScore() {
        var recent = newsStore.stream()
                .filter(n -> n.publishedAt().isAfter(LocalDateTime.now().minusHours(2))).toList();
        if (recent.isEmpty()) return 0;
        return recent.stream()
                .mapToDouble(n -> n.sentimentScore() * (n.isHighImpact() ? 2.0 : 1.0))
                .average().orElse(0);
    }
    public boolean hasHighImpactEvent() {
        return newsStore.stream()
                .filter(n -> n.publishedAt().isAfter(LocalDateTime.now().minusHours(6)))
                .anyMatch(NewsItem::eventAlert);
    }
    public boolean isNewsBearish() { return getMarketSentimentScore() < -0.3; }

    private boolean isMarketHours() {
        LocalTime now = LocalTime.now();
        DayOfWeek day = LocalDate.now().getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return false;
        return now.isAfter(LocalTime.of(8, 0)) && now.isBefore(LocalTime.of(16, 0));
    }
}
