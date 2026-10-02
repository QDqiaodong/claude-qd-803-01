package com.dyeing.mill.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.dyeing.mill.domain.CleaningRuleVersion;

public interface CleaningRuleVersionRepository extends JpaRepository<CleaningRuleVersion, Long> {

    @Query("select r from CleaningRuleVersion r where r.status = 'ACTIVE'")
    Optional<CleaningRuleVersion> findActive();

    @Query("select r from CleaningRuleVersion r order by r.id desc")
    List<CleaningRuleVersion> findAllOrdered();

    @Query("select r from CleaningRuleVersion r where r.versionCode = :versionCode")
    Optional<CleaningRuleVersion> findByCode(String versionCode);
}
