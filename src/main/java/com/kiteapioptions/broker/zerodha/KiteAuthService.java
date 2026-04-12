package com.kiteapioptions.broker.zerodha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kiteapioptions.broker.BrokerException;
import com.kiteapioptions.config.TradingProperties;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Manual Zerodha Kite login flow. Zerodha expects the user to login and generate a fresh token daily.
 */
@Service
public class KiteAuthService {

    private final TradingProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final KiteAccessTokenStore tokenStore;

    public KiteAuthService(TradingProperties properties, RestClient zerodhaRestClient,
                           ObjectMapper objectMapper, KiteAccessTokenStore tokenStore) {
        this.properties = properties;
        this.restClient = zerodhaRestClient;
        this.objectMapper = objectMapper;
        this.tokenStore = tokenStore;
    }

    public URI loginUrl() {
        if (isBlank(properties.broker().apiKey())) {
            throw new BrokerException("KITE_API_KEY is required to build the Kite login URL");
        }
        return UriComponentsBuilder.fromUriString(properties.broker().loginUrl())
                .queryParam("v", "3")
                .queryParam("api_key", properties.broker().apiKey())
                .build()
                .toUri();
    }

    public KiteLoginResult exchangeRequestToken(String requestToken) {
        if (isBlank(requestToken)) {
            throw new BrokerException("request_token is required");
        }
        if (isBlank(properties.broker().apiKey()) || isBlank(properties.broker().apiSecret())) {
            throw new BrokerException("KITE_API_KEY and KITE_API_SECRET are required to generate a session");
        }

        try {
            var form = new LinkedMultiValueMap<String, String>();
            form.add("api_key", properties.broker().apiKey());
            form.add("request_token", requestToken);
            form.add("checksum", checksum(requestToken));

            String body = restClient.post()
                    .uri("/session/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);

            JsonNode data = objectMapper.readTree(body).path("data");
            String accessToken = text(data, "access_token");
            String publicToken = text(data, "public_token");
            String userId = firstNonBlank(properties.broker().userId(), text(data, "user_id"));
            tokenStore.update(accessToken, publicToken, userId);
            return new KiteLoginResult(true, userId, accessToken, publicToken, Instant.now());
        } catch (Exception ex) {
            throw new BrokerException("Failed to generate Kite access token from request_token", ex);
        }
    }

    public Map<String, Object> diagnostics() {
        return Map.of(
                "apiKeyConfigured", !isBlank(properties.broker().apiKey()),
                "apiSecretConfigured", !isBlank(properties.broker().apiSecret()),
                "configuredAccessTokenPresent", !isBlank(properties.broker().accessToken()),
                "runtimeAccessTokenPresent", tokenStore.authenticated(),
                "redirectUrl", properties.broker().redirectUrl(),
                "callbackPath", properties.broker().callbackPath(),
                "dailyLoginRequirement", "Fresh manual login/access token is expected each trading day"
        );
    }

    private String checksum(String requestToken) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest((properties.broker().apiKey() + requestToken + properties.broker().apiSecret())
                .getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText();
    }

    private String firstNonBlank(String first, String second) {
        return isBlank(first) ? second : first;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
