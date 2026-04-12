package com.kiteapioptions.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ErrorEventRepository extends JpaRepository<ErrorEventEntity, Long> {
}
