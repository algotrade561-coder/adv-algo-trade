package com.algo.trade.config.usersettings;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserTradingSettingsRepository extends JpaRepository<UserTradingSettings, Long> {
    Optional<UserTradingSettings> findByUserId(Long userId);
    boolean existsByUserId(Long userId);
}
