package com.algo.trade.multiuser.ip;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserIpAllocationRepository extends JpaRepository<UserIpAllocation, Long> {
    Optional<UserIpAllocation> findByUserId(Long userId);
    boolean existsByUserId(Long userId);
    List<UserIpAllocation> findByStatus(IpAllocationStatus status);
    Optional<UserIpAllocation> findByPrivateIp(String privateIp);
}
