package com.kiteapioptions.broker.zerodha;

import com.kiteapioptions.config.TradingProperties;
import com.kiteapioptions.domain.ExecutionMode;
import com.kiteapioptions.domain.MarketDataMode;
import com.kiteapioptions.domain.TradingMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * Starts the local ngrok callback tunnel before Kite startup login begins.
 */
@Component
public class NgrokTunnelLifecycle implements ApplicationRunner, Ordered {

    private static final Logger log = LoggerFactory.getLogger(NgrokTunnelLifecycle.class);
    private static final int STARTUP_EXIT_CHECK_MILLIS = 500;
    private static final int SHUTDOWN_WAIT_SECONDS = 5;

    private final TradingProperties properties;
    private Process process;

    public NgrokTunnelLifecycle(TradingProperties properties) {
        this.properties = properties;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.broker().ngrokEnabled()) {
            log.info("ngrok startup skipped: trading.broker.ngrok-enabled=false");
            return;
        }
        if (!zerodhaRequired()) {
            log.info("ngrok startup skipped: mode={}, marketDataMode={}, executionMode={}",
                    properties.mode(), properties.marketDataMode(), properties.executionMode());
            return;
        }
        if (process != null && process.isAlive()) {
            log.info("ngrok already running: pid={}", process.pid());
            return;
        }

        String ngrokPath = properties.broker().ngrokPath();
        int httpPort = properties.broker().ngrokHttpPort();
        boolean uiEnabled = properties.broker().ngrokUiEnabled();
        Path logPath = Path.of("target", "ngrok.log");
        try {
            Files.createDirectories(logPath.getParent());
            ProcessBuilder builder = uiEnabled
                    ? new ProcessBuilder(
                            ngrokPath,
                            "start",
                            "--config",
                            properties.broker().ngrokConfigPath(),
                            properties.broker().ngrokCallbackTunnelName(),
                            properties.broker().ngrokUiTunnelName()
                    )
                    : new ProcessBuilder(ngrokPath, "http", String.valueOf(httpPort));
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logPath.toFile()));
            process = builder.start();
            Thread.sleep(STARTUP_EXIT_CHECK_MILLIS);
            if (!process.isAlive()) {
                int exitCode = process.exitValue();
                process = null;
                throw new IllegalStateException("ngrok exited during startup with exit code " + exitCode
                        + ". Check " + logPath.toAbsolutePath());
            }
            if (uiEnabled) {
                log.info("ngrok started: path={}, config={}, callbackTunnel={}, callbackPort={}, uiTunnel={}, uiPort={}, pid={}, log={}",
                        ngrokPath,
                        properties.broker().ngrokConfigPath(),
                        properties.broker().ngrokCallbackTunnelName(),
                        httpPort,
                        properties.broker().ngrokUiTunnelName(),
                        properties.broker().ngrokUiHttpPort(),
                        process.pid(),
                        logPath.toAbsolutePath());
            } else {
                log.info("ngrok started: path={}, httpPort={}, pid={}, log={}",
                        ngrokPath, httpPort, process.pid(), logPath.toAbsolutePath());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start ngrok using " + ngrokPath
                    + ". Set NGROK_PATH or trading.broker.ngrok-path to the executable path.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while starting ngrok", e);
        }
    }

    @EventListener
    public void onContextClosed(ContextClosedEvent event) {
        stop();
    }

    private boolean zerodhaRequired() {
        return properties.mode() == TradingMode.LIVE
                || properties.marketDataMode() == MarketDataMode.ZERODHA
                || properties.executionMode() == ExecutionMode.ZERODHA;
    }

    private void stop() {
        if (process == null) {
            return;
        }
        if (!process.isAlive()) {
            process = null;
            return;
        }
        long pid = process.pid();
        log.info("Stopping ngrok: pid={}", pid);
        process.destroy();
        try {
            if (!process.waitFor(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("ngrok did not stop within {} seconds; forcing shutdown: pid={}",
                        SHUTDOWN_WAIT_SECONDS, pid);
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        } finally {
            process = null;
        }
    }
}
