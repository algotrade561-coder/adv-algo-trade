package com.algo.trade.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One-shot schema migration — ensures columns we added in code exist in the DB.
 *
 * Hibernate's ddl-auto=update is unreliable on H2 for adding NOT NULL columns
 * to a table that already has rows. This runner uses idempotent ALTER TABLE
 * statements that work on both H2 and PostgreSQL.
 *
 * Add new columns here as the schema evolves — each block is safe to re-run.
 */
@Configuration
public class SchemaMigrationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrationRunner.class);

    @Bean
    public ApplicationRunner authSchemaMigration(JdbcTemplate jdbc) {
        return args -> {
            // ── app_users ──
            addColumn(jdbc, "app_users", "enabled",     "BOOLEAN DEFAULT TRUE");
            addColumn(jdbc, "app_users", "created_at",  "TIMESTAMP");
            backfill(jdbc,  "UPDATE app_users SET enabled = TRUE WHERE enabled IS NULL");
            backfill(jdbc,  "UPDATE app_users SET created_at = CURRENT_TIMESTAMP WHERE created_at IS NULL");

            // ── user_broker_config ──
            addColumn(jdbc, "user_broker_config", "primary_account", "BOOLEAN DEFAULT FALSE");
            // Widen secret columns that now hold ciphertext (idempotent for H2 / PG)
            widenColumn(jdbc, "user_broker_config", "api_secret",         "VARCHAR(1024)");
            widenColumn(jdbc, "user_broker_config", "access_token",       "VARCHAR(2048)");
            widenColumn(jdbc, "user_broker_config", "request_token",      "VARCHAR(1024)");
            widenColumn(jdbc, "user_broker_config", "telegram_bot_token", "VARCHAR(1024)");
            widenColumn(jdbc, "user_broker_config", "webhook_url",        "VARCHAR(2048)");
            widenColumn(jdbc, "user_broker_config", "api_key",            "VARCHAR(512)");
            // One-time Kite setup instruction (redirect URL + public IP) idempotency marker,
            // stamped on first Telegram link.
            addColumn(jdbc, "user_broker_config", "kite_setup_sent_at", "TIMESTAMP");
            backfill(jdbc,  "UPDATE user_broker_config SET primary_account = FALSE WHERE primary_account IS NULL");
        };
    }

    private void addColumn(JdbcTemplate jdbc, String table, String column, String typeAndDefault) {
        try {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS " + column + " " + typeAndDefault);
            log.info("[Schema] ensured column {}.{} ({})", table, column, typeAndDefault);
        } catch (Exception e) {
            // Some DBs don't support IF NOT EXISTS — try plain ADD and swallow duplicate-column errors
            try {
                jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + typeAndDefault);
                log.info("[Schema] added column {}.{}", table, column);
            } catch (Exception inner) {
                log.debug("[Schema] column {}.{} already present or table missing: {}", table, column, inner.getMessage());
            }
        }
    }

    private void widenColumn(JdbcTemplate jdbc, String table, String column, String newType) {
        // H2 + PG both accept ALTER COLUMN ... TYPE / SET DATA TYPE — try both, swallow failures
        try {
            jdbc.execute("ALTER TABLE " + table + " ALTER COLUMN " + column + " " + newType);
            log.debug("[Schema] widened {}.{} to {}", table, column, newType);
        } catch (Exception h2) {
            try {
                jdbc.execute("ALTER TABLE " + table + " ALTER COLUMN " + column + " TYPE " + newType);
                log.debug("[Schema] widened {}.{} to {} (PG syntax)", table, column, newType);
            } catch (Exception pg) {
                log.debug("[Schema] could not widen {}.{}: {}", table, column, pg.getMessage());
            }
        }
    }

    private void backfill(JdbcTemplate jdbc, String sql) {
        try {
            int rows = jdbc.update(sql);
            if (rows > 0) log.info("[Schema] backfill: {} rows ← {}", rows, sql);
        } catch (Exception e) {
            log.debug("[Schema] backfill skipped: {}", e.getMessage());
        }
    }
}
