package com.algo.trade.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface UserBrokerConfigRepository extends JpaRepository<UserBrokerConfig, Long> {
    Optional<UserBrokerConfig> findByUserId(Long userId);
    List<UserBrokerConfig> findByTradingEnabled(boolean enabled);
    boolean existsByUserId(Long userId);
}
