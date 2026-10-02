package com.dyeing.mill.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dyeing.mill.domain.ScheduleLock;

public interface ScheduleLockRepository extends JpaRepository<ScheduleLock, Long> {

    @Query("select l from ScheduleLock l where l.deleted = 0 order by l.vatId, l.startTime")
    List<ScheduleLock> findAllActive();

    @Query("select l from ScheduleLock l where l.deleted = 0 and l.cartId = :cartId")
    Optional<ScheduleLock> findActiveByCart(@Param("cartId") Long cartId);

    @Query("select l from ScheduleLock l where l.deleted = 0 and l.vatId = :vatId order by l.startTime")
    List<ScheduleLock> findActiveByVat(@Param("vatId") Long vatId);
}
