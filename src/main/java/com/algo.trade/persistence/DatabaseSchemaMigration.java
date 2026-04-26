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
}
