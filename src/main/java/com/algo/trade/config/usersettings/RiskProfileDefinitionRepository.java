package com.algo.trade.config.usersettings;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RiskProfileDefinitionRepository extends JpaRepository<RiskProfileDefinition, String> {
}
