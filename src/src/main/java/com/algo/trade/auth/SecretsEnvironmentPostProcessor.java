package com.algo.trade.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Loads ONLY four shared secrets from data/trading-secrets.properties into the
 * Spring Environment, very early in boot — before SecurityConfig + Telegram
 * services pick up @Value placeholders:
 *
 *   TELEGRAM_BOT_TOKEN
 *   TELEGRAM_BOT_USERNAME
 *   GOOGLE_CLIENT_ID
 *   GOOGLE_CLIENT_SECRET
 *
 * Per-user broker credentials (apiKey/apiSecret) and per-user Telegram chat IDs
 * stay in the database — this file is for app-level shared bot + OAuth client.
 *
 * Wired via META-INF/spring.factories so it runs before any Configuration is
 * processed. Missing file or missing keys are tolerated silently.
 */
public class SecretsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final Path SECRETS_PATH = Path.of("data", "trading-secrets.properties");

    /** Mapping: property key in file → list of Spring property names to publish */
    private static final Map<String, String[]> EXPORTS = Map.of(
            "TELEGRAM_BOT_TOKEN",       new String[]{"TELEGRAM_BOT_TOKEN", "trading.telegram.bot-token"},
            "TELEGRAM_BOT_USERNAME",    new String[]{"TELEGRAM_BOT_USERNAME", "trading.telegram.bot-username"},
            "GOOGLE_CLIENT_ID",         new String[]{"GOOGLE_CLIENT_ID"},
            "GOOGLE_CLIENT_SECRET",     new String[]{"GOOGLE_CLIENT_SECRET"}
    );

    /** Also accept these legacy lowercase keys already in the file */
    private static final Map<String, String> LEGACY_ALIASES = Map.of(
            "trading.telegram.bot-token",    "TELEGRAM_BOT_TOKEN",
            "trading.telegram.bot-username", "TELEGRAM_BOT_USERNAME"
    );

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        if (!Files.isRegularFile(SECRETS_PATH)) return;
        Properties p = new Properties();
        try (var in = Files.newInputStream(SECRETS_PATH)) {
            p.load(in);
        } catch (IOException ex) {
            return;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> e : EXPORTS.entrySet()) {
            String value = p.getProperty(e.getKey());
            if (value == null || value.isBlank()) {
                String alias = findKeyByValue(LEGACY_ALIASES, e.getKey());
                if (alias != null) value = p.getProperty(alias);
            }
            if (value != null && !value.isBlank()) {
                String cleaned = unescape(value);
                for (String target : e.getValue()) out.put(target, cleaned);
            }
        }
        if (!out.isEmpty()) {
            env.getPropertySources().addFirst(new MapPropertySource("tradingSecretsFile", out));
        }
    }

    private static String findKeyByValue(Map<String, String> m, String value) {
        return m.entrySet().stream().filter(e -> e.getValue().equals(value))
                .map(Map.Entry::getKey).findFirst().orElse(null);
    }

    /** Properties files escape ':' as '\:' — undo it for tokens like "12345:ABCdef". */
    private static String unescape(String v) {
        return v == null ? null : v.replace("\\:", ":");
    }
}
