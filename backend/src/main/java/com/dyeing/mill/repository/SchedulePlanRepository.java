package com.dyeing.mill.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.dyeing.mill.domain.SchedulePlan;

public interface SchedulePlanRepository extends JpaRepository<SchedulePlan, Long> {

    @Query("select p from SchedulePlan p where p.deleted = 0 and p.planKind = 'DRAFT' order by p.id desc")
    List<SchedulePlan> findDrafts();

    @Query("select p from SchedulePlan p where p.deleted = 0 and p.planKind = 'SNAPSHOT' order by p.id desc")
    List<SchedulePlan> findSnapshots();

    @Query("select p from SchedulePlan p where p.deleted = 0 order by p.planKind desc, p.id desc")
    List<SchedulePlan> findAllActive();

    @Query("select p from SchedulePlan p where p.deleted = 0 and p.status = 'REFERENCE'")
    Optional<SchedulePlan> findReference();
}
