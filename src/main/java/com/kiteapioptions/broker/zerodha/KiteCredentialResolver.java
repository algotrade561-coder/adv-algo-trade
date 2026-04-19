package com.kiteapioptions.broker.zerodha;

import com.kiteapioptions.config.TradingProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.springframework.stereotype.Component;

@Component
public class KiteCredentialResolver {

    private static final Path LOCAL_SECRETS_PATH = Path.of("data", "trading-secrets.properties");

    private final TradingProperties properties;

    public KiteCredentialResolver(TradingProperties properties) {
        this.properties = properties;
    }

    public String apiKey() {
        return resolve(properties.broker().apiKey(), "trading.broker.api-key", "KITE_API_KEY");
    }

    public String apiSecret() {
        return resolve(properties.broker().apiSecret(), "trading.broker.api-secret", "KITE_API_SECRET");
    }

    public String userId() {
        return resolve(properties.broker().userId(), "trading.broker.user-id", "KITE_USER_ID");
    }

    public boolean apiKeyConfigured() {
        return hasText(apiKey());
    }

    private String resolve(String configuredValue, String propertyName, String environmentVariable) {
        String configured = resolvePlaceholder(configuredValue);
        if (hasText(configured)) {
            return configured;
        }
        String environmentValue = System.getenv(environmentVariable);
        if (hasText(environmentValue)) {
            return environmentValue;
        }
        String fileValue = resolvePlaceholder(localSecret(propertyName));
        return hasText(fileValue) ? fileValue : "";
    }

    private String localSecret(String propertyName) {
        if (!Files.isRegularFile(LOCAL_SECRETS_PATH)) {
            return "";
        }
        Properties secrets = new Properties();
        try (var input = Files.newInputStream(LOCAL_SECRETS_PATH)) {
            secrets.load(input);
            return secrets.getProperty(propertyName, "");
        } catch (IOException ex) {
            return "";
        }
    }

    private String resolvePlaceholder(String value) {
        if (!hasText(value)) {
            return "";
        }
        String trimmed = value.trim();
        if (!trimmed.startsWith("${") || !trimmed.endsWith("}")) {
            return trimmed;
        }
        String expression = trimmed.substring(2, trimmed.length() - 1);
        int separator = expression.indexOf(':');
        String environmentVariable = separator >= 0 ? expression.substring(0, separator) : expression;
        String defaultValue = separator >= 0 ? expression.substring(separator + 1) : "";
        String environmentValue = System.getenv(environmentVariable);
        return hasText(environmentValue) ? environmentValue : defaultValue;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
