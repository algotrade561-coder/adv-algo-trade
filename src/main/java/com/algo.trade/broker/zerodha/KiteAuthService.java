package com.algo.trade.broker.zerodha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.algo.trade.broker.BrokerException;
import com.algo.trade.config.TradingProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Manual Zerodha Kite login flow. Zerodha expects the user to login and generate a fresh token daily.
 */
@Service
public class KiteAuthService {

    private static final Duration LOGIN_TIMEOUT = Duration.ofMinutes(2);
    private static final Logger log = LoggerFactory.getLogger(KiteAuthService.class);
    private static final Path LOCAL_SECRETS_PATH = Path.of("data", "trading-secrets.properties");

    private final TradingProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final KiteAccessTokenStore tokenStore;
    private final KiteCredentialResolver credentialResolver;
    private final ArrayBlockingQueue<KiteLoginResult> loginResultQueue = new ArrayBlockingQueue<>(1);
    private HttpServer callbackServer;

    public KiteAuthService(TradingProperties properties, RestClient zerodhaRestClient,
                           ObjectMapper objectMapper, KiteAccessTokenStore tokenStore,
                           KiteCredentialResolver credentialResolver) {
        this.properties = properties;
        this.restClient = zerodhaRestClient;
        this.objectMapper = objectMapper;
        this.tokenStore = tokenStore;
        this.credentialResolver = credentialResolver;
    }

    public KiteLoginResult login()  {
         printStartupDiagnostics();
        if (tokenStore.authenticated()) {
            if (validateCurrentSession()) {
                String userId = tokenStore.userId().orElse(credentialResolver.userId());
                log.info("Using configured/runtime Kite access token: userId={}", valueOrMissing(userId));
                return new KiteLoginResult(true, userId, tokenStore.accessToken().orElse(""),
                        tokenStore.publicToken().orElse(""), tokenStore.updatedAt().orElse(Instant.now()));
            }
            log.info("Configured/runtime Kite access token is invalid. Starting manual login.");
        }

        URI loginUrl = loginUrl();
        log.info("Open this Kite login URL if the browser does not launch: {}", loginUrl);
        loginResultQueue.clear();
        try {
            startCallbackListener();
            openBrowser(loginUrl);
            return awaitLoginResult(loginUrl);
        } catch (Exception e) {
            log.warn("Kite login failed: {}", e.getMessage(), e);
            throw new BrokerException("Kite login failed", e);
        } finally {
            stopCallbackListener();
        }
    }

    public synchronized void startCallbackListener() throws IOException {
        if (callbackServer != null) {
            return;
        }

        callbackServer = HttpServer.create(new InetSocketAddress("localhost", 8081), 0);
        callbackServer.createContext("/", this::handleCallback);
        callbackServer.start();
        System.out.println("Kite callback listener started on http://localhost:8089/");
    }

    public synchronized void stopCallbackListener() {
        if (callbackServer == null) {
            return;
        }

        callbackServer.stop(0);
        callbackServer = null;
        log.info("Kite callback listener stopped");
    }

    public URI loginUrl() {
        String apiKey = credentialResolver.apiKey();
        if (isBlank(apiKey)) {
            log.warn("Kite login URL request failed: API key is not configured");
            throw new BrokerException("KITE_API_KEY is required to build the Kite login URL");
        }
        URI uri = UriComponentsBuilder.fromUriString(properties.broker().loginUrl())
                .queryParam("v", "3")
                .queryParam("api_key", apiKey)
                .build()
                .toUri();
        log.info("Kite login URL generated: loginUrlBase={}, redirectUrl={}",
                properties.broker().loginUrl(), properties.broker().redirectUrl());
        return uri;
    }

    public Map<String, Object> initiateLoginFlow() throws IOException {
        URI loginUrl = loginUrl();
        loginResultQueue.clear();
        log.info("Kite login URL prepared for client-side launch: loginUrl={}", loginUrl);
        return Map.of(
                "loginUrl", loginUrl.toString(),
                "launched", false,
                "message", "Open Kite login in this browser, complete authentication, and allow the callback to refresh the access token.",
                "diagnostics", diagnostics()
        );
    }

    public KiteLoginResult currentSession() {
        if (tokenStore.authenticated() && validateCurrentSession()) {
            String userId = tokenStore.userId().orElse(credentialResolver.userId());
            return new KiteLoginResult(true, userId, tokenStore.accessToken().orElse(""),
                    tokenStore.publicToken().orElse(""), tokenStore.updatedAt().orElse(Instant.now()));
        }
        return new KiteLoginResult(false, "", "", "", Instant.now());
    }

    public boolean apiKeyConfigured() {
        return credentialResolver.apiKeyConfigured();
    }

    public Map<String, Object> firstRunSetup() {
        ensureLocalSecretsFile();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("loginUrl", "");
        response.put("setupRequired", true);
        response.put("message", "Kite API credentials are required before a Kite login URL can be generated.");
        response.put("secretsFile", LOCAL_SECRETS_PATH.toAbsolutePath().normalize().toString());
        response.put("requiredProperties", List.of("trading.broker.api-key", "trading.broker.api-secret"));
        response.put("environmentVariables", List.of("KITE_API_KEY", "KITE_API_SECRET"));
        response.put("diagnostics", diagnostics());
        return response;
    }

    public Map<String, Object> firstRunSessionSetup() {
        Map<String, Object> response = new LinkedHashMap<>(firstRunSetup());
        response.put("authenticated", false);
        response.put("userId", "");
        response.put("authenticatedAt", "");
        return response;
    }

    private void ensureLocalSecretsFile() {
        try {
            Files.createDirectories(LOCAL_SECRETS_PATH.getParent());
            if (!Files.isRegularFile(LOCAL_SECRETS_PATH)) {
                Files.writeString(LOCAL_SECRETS_PATH, String.join(System.lineSeparator(),
                        "# Local trading secrets. Do not commit this file.",
                        "trading.broker.api-key=${KITE_API_KEY:}",
                        "trading.broker.api-secret=${KITE_API_SECRET:}",
                        "trading.broker.access-token=${KITE_ACCESS_TOKEN:}",
                        "trading.broker.user-id=${KITE_USER_ID:}",
                        ""
                ), StandardCharsets.UTF_8);
                log.info("Created first-run Kite secrets template: {}", LOCAL_SECRETS_PATH.toAbsolutePath());
                return;
            }
            String content = Files.readString(LOCAL_SECRETS_PATH, StandardCharsets.UTF_8);
            StringBuilder missing = new StringBuilder();
            appendMissingProperty(content, missing, "trading.broker.api-key=${KITE_API_KEY:}");
            appendMissingProperty(content, missing, "trading.broker.api-secret=${KITE_API_SECRET:}");
            appendMissingProperty(content, missing, "trading.broker.access-token=${KITE_ACCESS_TOKEN:}");
            appendMissingProperty(content, missing, "trading.broker.user-id=${KITE_USER_ID:}");
            if (!missing.isEmpty()) {
                String separator = content.endsWith(System.lineSeparator()) || content.isEmpty()
                        ? ""
                        : System.lineSeparator();
                Files.writeString(LOCAL_SECRETS_PATH, separator + missing, StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.APPEND);
                log.info("Added missing Kite secrets placeholders to {}", LOCAL_SECRETS_PATH.toAbsolutePath());
            }
        } catch (IOException ex) {
            log.warn("Could not create first-run Kite secrets template at {}: {}",
                    LOCAL_SECRETS_PATH.toAbsolutePath(), ex.getMessage());
        }
    }

    private void appendMissingProperty(String content, StringBuilder missing, String propertyLine) {
        String key = propertyLine.substring(0, propertyLine.indexOf('='));
        boolean present = content.lines()
                .map(String::trim)
                .anyMatch(line -> line.startsWith(key + "="));
        if (!present) {
            missing.append(propertyLine).append(System.lineSeparator());
        }
    }

    public KiteLoginResult exchangeRequestToken(String requestToken) {
        log.info("Kite request token exchange started: requestTokenPresent={}", !isBlank(requestToken));
        if (isBlank(requestToken)) {
            log.warn("Kite request token exchange rejected: request_token is blank");
            throw new BrokerException("request_token is required");
        }
        String apiKey = credentialResolver.apiKey();
        String apiSecret = credentialResolver.apiSecret();
        if (isBlank(apiKey) || isBlank(apiSecret)) {
            log.warn("Kite request token exchange rejected: apiKeyConfigured={}, apiSecretConfigured={}",
                    !isBlank(apiKey), !isBlank(apiSecret));
            throw new BrokerException("KITE_API_KEY and KITE_API_SECRET are required to generate a session");
        }

        try {
            var form = new LinkedMultiValueMap<String, String>();
            form.add("api_key", apiKey);
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
            String userId = firstNonBlank(credentialResolver.userId(), text(data, "user_id"));
            tokenStore.update(accessToken, publicToken, userId);
            log.info("Kite request token exchange completed: userId={}, accessTokenCaptured={}, publicTokenCaptured={}",
                    userId, !isBlank(accessToken), !isBlank(publicToken));
            KiteLoginResult result = new KiteLoginResult(true, userId, accessToken, publicToken, Instant.now());
            loginResultQueue.offer(result);
            return result;
        } catch (Exception ex) {
            log.warn("Kite request token exchange failed: {}", ex.getMessage());
            throw new BrokerException("Failed to generate Kite access token from request_token", ex);
        }
    }

    public boolean validateCurrentSession() {
        if (!tokenStore.authenticated()) {
            return false;
        }
        try {
            restClient.get()
                    .uri("/user/profile")
                    .headers(this::applyAuthHeaders)
                    .retrieve()
                    .body(String.class);
            log.info("Kite access token validation succeeded: userId={}",
                    valueOrMissing(tokenStore.userId().orElse(credentialResolver.userId())));
            return true;
        } catch (RestClientException ex) {
            log.warn("Kite access token validation failed. Clearing persisted token: {}", ex.getMessage());
            tokenStore.clear();
            return false;
        }
    }

    private void handleCallback(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseQueryParams(exchange.getRequestURI().getRawQuery());
        String status = params.get("status");
        String requestToken = params.get("request_token");

        String body;
        int statusCode;
        if (!"success".equalsIgnoreCase(status) || isBlank(requestToken)) {
            statusCode = 400;
            body = "Kite callback listener is running, but no successful request_token was received. "
                    + "Complete Kite login and let Zerodha redirect back to this URL.";
            log.warn("Kite raw callback rejected: status={}, requestTokenPresent={}",
                    status, !isBlank(requestToken));
        } else {
            try {
                KiteLoginResult result = exchangeRequestToken(requestToken);
                statusCode = 200;
                body = "Kite login completed for user " + result.userId()
                        + ". You can close this window and return to the application.";
                log.info("Kite raw callback completed: userId={}", result.userId());
            } catch (Exception ex) {
                statusCode = 500;
                body = "Kite login token exchange failed. You can close this window and check the application logs.";
                log.warn("Kite raw callback token exchange failed: {}", ex.getMessage(), ex);
            }
        }

        byte[] responseBytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(responseBytes);
        }
        if (statusCode == 200) {
            stopCallbackListener();
        }
    }

    public Map<String, Object> diagnostics() {
        log.info("Kite auth diagnostics requested: apiKeyConfigured={}, apiSecretConfigured={}, configuredAccessTokenPresent={}, runtimeAccessTokenPresent={}",
                !isBlank(credentialResolver.apiKey()), !isBlank(credentialResolver.apiSecret()),
                !isBlank(properties.broker().accessToken()), tokenStore.authenticated());
        return Map.of(
                "apiKeyConfigured", !isBlank(credentialResolver.apiKey()),
                "apiSecretConfigured", !isBlank(credentialResolver.apiSecret()),
                "configuredAccessTokenPresent", !isBlank(properties.broker().accessToken()),
                "runtimeAccessTokenPresent", tokenStore.authenticated(),
                "redirectUrl", properties.broker().redirectUrl(),
                "callbackPath", properties.broker().callbackPath(),
                "dailyLoginRequirement", "Fresh manual login/access token is expected each trading day"
        );
    }

    private KiteLoginResult awaitLoginResult(URI loginUrl) {
        try {
            KiteLoginResult result = loginResultQueue.poll(LOGIN_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (result == null || isBlank(result.accessToken())) {
                throw new BrokerException("Timed out waiting for Kite login callback on "
                        + properties.broker().redirectUrl()
                        + ". Verify this redirect URL is configured in the Kite developer console. Login URL: "
                        + loginUrl);
            }
            log.info("Kite login completed: userId={}", result.userId());
            return result;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BrokerException("Interrupted while waiting for Kite login callback", ex);
        }
    }

    private Map<String, String> parseQueryParams(String query) {
        Map<String, String> params = new HashMap<>();
        if (isBlank(query)) {
            return params;
        }

        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = decode(parts[0]);
            String value = parts.length > 1 ? decode(parts[1]) : "";
            params.put(key, value);
        }
        return params;
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private int callbackPort(URI redirectUri) {
        if (redirectUri.getPort() > 0) {
            return redirectUri.getPort();
        }
        if ("https".equalsIgnoreCase(redirectUri.getScheme())) {
            return 443;
        }
        return 80;
    }

    private void openBrowser(URI loginUrl) {
        if (openBrowserWithProcess(loginUrl)) {
            return;
        }

        try {
            if (!Desktop.isDesktopSupported()) {
                log.info("Desktop browser launch is not supported. Open Kite login URL manually: {}", loginUrl);
                return;
            }
            Desktop.getDesktop().browse(loginUrl);
            log.info("Kite login URL opened in browser");
        } catch (Exception ex) {
            log.info("Could not open browser automatically. Open Kite login URL manually: {}", loginUrl);
        }
    }

    private boolean openBrowserWithProcess(URI loginUrl) {
        String osName = System.getProperty("os.name", "").toLowerCase();
        String url = loginUrl.toString();
        String[] command;
        if (osName.contains("win")) {
            command = new String[]{"rundll32", "url.dll,FileProtocolHandler", url};
        } else if (osName.contains("mac")) {
            command = new String[]{"open", url};
        } else {
            command = new String[]{"xdg-open", url};
        }

        try {
            new ProcessBuilder(command).start();
            log.info("Kite login URL opened using OS browser command");
            return true;
        } catch (Exception ex) {
            log.info("OS browser command failed. Falling back to Desktop API. reason={}", ex.getMessage());
            return false;
        }
    }

    private void printStartupDiagnostics() {
        log.info("Kite auth diagnostics: apiKeyConfigured={}, apiSecretConfigured={}, userId={}, accessTokenConfigured={}, runtimeAccessTokenPresent={}, redirectUrl={}, dailyLoginRequirement={}",
                mask(credentialResolver.apiKey()),
                yesNo(!isBlank(credentialResolver.apiSecret())),
                valueOrMissing(credentialResolver.userId()),
                yesNo(!isBlank(properties.broker().accessToken())),
                tokenStore.authenticated(),
                properties.broker().redirectUrl(),
                "Fresh manual login/access token is typically required each trading day");
    }

    private void applyAuthHeaders(HttpHeaders headers) {
        headers.set("X-Kite-Version", "3");
        headers.set(HttpHeaders.AUTHORIZATION,
                "token " + credentialResolver.apiKey() + ":" + tokenStore.accessToken().orElseThrow());
    }

    private String checksum(String requestToken) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest((credentialResolver.apiKey() + requestToken + credentialResolver.apiSecret())
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

    private String yesNo(boolean value) {
        return value ? "yes" : "no";
    }

    private String valueOrMissing(String value) {
        return isBlank(value) ? "<missing>" : value;
    }

    private String mask(String value) {
        if (isBlank(value)) {
            return "<missing>";
        }
        if (value.length() <= 4) {
            return "****";
        }
        return value.substring(0, 2) + "****" + value.substring(value.length() - 2);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
