package com.algo.trade.underlying;

import com.algo.trade.domain.UnderlyingSymbol;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UnderlyingConfigRepository extends JpaRepository<UnderlyingConfig, UnderlyingSymbol> {
    List<UnderlyingConfig> findByEnabledTrue();
}
