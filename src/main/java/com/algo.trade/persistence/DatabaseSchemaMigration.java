package com.algo.trade.persistence;

import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DatabaseSchemaMigration {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSchemaMigration.class);

    private final DataSource dataSource;

    public DatabaseSchemaMigration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void migrate() {
        widenStrategyDecisionReasons();
        addConfigVersionColumn();
        addPaperTradeColumn();
        disableSellingSpreadStrategies();
    }

    private void addConfigVersionColumn() {
        String sql = "alter table if exists global_config add column if not exists config_version integer default 0";
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
            log.info("Database schema migration applied: global_config.config_version added");
        } catch (Exception ex) {
            log.debug("Database schema migration skipped or failed: sql={}, message={}", sql, ex.getMessage());
        }
    }

    private void widenStrategyDecisionReasons() {
        String sql = "alter table strategy_decision_entity alter column reasons clob";
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
            log.info("Database schema migration applied: strategy_decision_entity.reasons -> CLOB");
        } catch (Exception ex) {
            log.debug("Database schema migration skipped or failed: sql={}, message={}", sql, ex.getMessage());
        }
    }

    private void addPaperTradeColumn() {
        String sql = "alter table strategy_decision_entity add column if not exists paper_trade boolean default false";
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
            log.info("Database schema migration applied: strategy_decision_entity.paper_trade added");
        } catch (Exception ex) {
            log.debug("Database schema migration skipped or failed: sql={}, message={}", sql, ex.getMessage());
        }
    }

    private void disableSellingSpreadStrategies() {
        // One-time migration: check if already applied by looking at paper_trading flag
        // If BULL_CALL_SPREAD is enabled and NOT paper_trading, it hasn't been migrated yet
        String checkSql = "select count(*) from strategy_config " +
                          "where strategy_type in ('BULL_CALL_SPREAD', 'BEAR_PUT_SPREAD') " +
                          "and enabled = true and paper_trading = false";
        String sql = "update strategy_config set enabled = false " +
                     "where strategy_type in ('BULL_CALL_SPREAD', 'BEAR_PUT_SPREAD') " +
                     "and enabled = true and paper_trading = false";
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            var rs = statement.executeQuery(checkSql);
            if (rs.next() && rs.getInt(1) > 0) {
                int updated = statement.executeUpdate(sql);
                log.info("Database migration: disabled {} selling spread strategies (BULL_CALL_SPREAD, BEAR_PUT_SPREAD) — they are option selling strategies", updated);
            }
        } catch (Exception ex) {
            log.debug("Database schema migration skipped or failed: message={}", ex.getMessage());
        }
    }
}
