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
        dropStrategyTypeUniqueIndex();
    }

    private void dropStrategyTypeUniqueIndex() {
        // Drop the old single-column unique index on strategy_type so the new composite
        // unique constraint (strategy_type + underlying) can allow one config per underlying.
        // No-op on a fresh DB — idx_sc_type never existed.
        String sql = "drop index if exists idx_sc_type";
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
            log.info("Database schema migration: dropped idx_sc_type (no-op if already absent)");
        } catch (Exception ex) {
            log.debug("Database schema migration skipped: sql={}, message={}", sql, ex.getMessage());
        }
    }
}
