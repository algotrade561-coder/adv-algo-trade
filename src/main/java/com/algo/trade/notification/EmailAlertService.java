package com.algo.trade.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Email Alert Service — sends critical trading alerts via email.
 *
 * Only used for CRITICAL alerts (circuit breaker, daily loss limit hit, system errors).
 * Regular trade notifications go through Telegram and Webhook.
 *
 * Configure in application.yml:
 *   spring.mail.host, spring.mail.port, spring.mail.username, spring.mail.password
 *   trading.alerts.email.enabled: true
 *   trading.alerts.email.to: your-email@example.com
 */
@Component
public class EmailAlertService {

    private static final Logger log = LoggerFactory.getLogger(EmailAlertService.class);

    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    @Value("${trading.alerts.email.enabled:false}")
    private boolean enabled;

    @Value("${trading.alerts.email.to:}")
    private String recipientEmail;

    @Value("${trading.alerts.email.from:algotrade@noreply.com}")
    private String fromEmail;

    public EmailAlertService(ObjectProvider<JavaMailSender> mailSenderProvider) {
        this.mailSenderProvider = mailSenderProvider;
    }

    /**
     * Send a critical alert email.
     */
    public void sendCriticalAlert(String subject, String body) {
        if (!enabled || recipientEmail == null || recipientEmail.isBlank()) return;
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            log.warn("[Email] Alert requested but no JavaMailSender configured (set spring.mail.* to enable): {}", subject);
            return;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(recipientEmail);
            message.setSubject("[ALGO TRADE CRITICAL] " + subject);
            message.setText(body + "\n\n---\nSent by Unified Algo Trade System");

            mailSender.send(message);
            log.info("[Email] Critical alert sent: {}", subject);
        } catch (Exception e) {
            log.error("[Email] Failed to send alert: {}", e.getMessage());
        }
    }

    /**
     * Send daily summary email.
     */
    public void sendDailySummary(String summaryText) {
        if (!enabled || recipientEmail == null || recipientEmail.isBlank()) return;
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            log.warn("[Email] Daily summary requested but no JavaMailSender configured (set spring.mail.* to enable)");
            return;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(recipientEmail);
            message.setSubject("[ALGO TRADE] Daily Trading Summary");
            message.setText(summaryText);

            mailSender.send(message);
            log.info("[Email] Daily summary sent");
        } catch (Exception e) {
            log.error("[Email] Failed to send daily summary: {}", e.getMessage());
        }
    }
}
