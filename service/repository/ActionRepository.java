package com.wp.wdp.auto.response.event.service.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.wp.wdp.auto.response.event.service.entity.ActionEntity;

public interface ActionRepository extends JpaRepository<ActionEntity, Long> {

    Optional<ActionEntity> findByCaseNumberAndActionSequence(String caseNumber, String actionSequence);

    @Query(value = "SELECT * FROM wdp.action WHERE i_case IN (:caseNumbers) AND i_action_seq = '01' "
            + "AND c_reason IS NOT NULL", nativeQuery = true)
    List<ActionEntity> findFirstActionsWithReason(@Param("caseNumbers") Collection<String> caseNumbers);
}
