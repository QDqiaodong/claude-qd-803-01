package com.dyeing.mill.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dyeing.mill.domain.VatMaintenance;

public interface VatMaintenanceRepository extends JpaRepository<VatMaintenance, Long> {

    /** 维护窗按缸与开始时间排序，排程固定块用（@SQLRestriction 不适用，手写过滤 deleted） */
    @Query("select m from VatMaintenance m where m.deleted = 0 order by m.vatId, m.startTime")
    List<VatMaintenance> findAllActive();

    @Query("select m from VatMaintenance m where m.deleted = 0 and m.vatId = :vatId order by m.startTime")
    List<VatMaintenance> findActiveByVat(@Param("vatId") Long vatId);

    @Query("select m from VatMaintenance m where m.deleted = 0 and m.vatId = :vatId "
            + "and m.startTime < :end and m.endTime > :start")
    List<VatMaintenance> findOverlap(@Param("vatId") Long vatId,
                                     @Param("start") java.time.LocalDateTime start,
                                     @Param("end") java.time.LocalDateTime end);
}
