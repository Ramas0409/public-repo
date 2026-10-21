package com.wp.wdp.auto.response.event.service.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.wp.wdp.auto.response.event.service.entity.CaseEntity;

public interface CaseRepository extends JpaRepository<CaseEntity, Long> {

    Optional<CaseEntity> findByCaseNumber(String caseNumber);
}
