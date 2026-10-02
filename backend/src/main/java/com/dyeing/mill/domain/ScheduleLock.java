package com.dyeing.mill.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 换色排程人工锁定段：调度员已承诺的布车 + 染缸 + 时段。
 * 重新计算时锁定段是不可移动的固定块，只允许调整未锁定的未来区段；
 * 同一布车至多一条有效锁定（uk_lock_active_cart）。
 */
@Data
@Entity
@Table(name = "schedule_lock")
public class ScheduleLock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cart_id", nullable = false)
    private Long cartId;

    @Column(name = "cart_code", nullable = false, length = 32)
    private String cartCode;

    @Column(name = "vat_id", nullable = false)
    private Long vatId;

    @Column(name = "vat_code", nullable = false, length = 32)
    private String vatCode;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalDateTime endTime;

    @Column(name = "note", length = 200)
    private String note;

    /** 锁定时适用的规则版本，历史锁定始终按当时口径解释 */
    @Column(name = "rule_version_id", nullable = false)
    private Long ruleVersionId;

    @Column(name = "locked_by", length = 32)
    private String lockedBy;

    @Column(name = "locked_time")
    private LocalDateTime lockedTime;

    @Column(name = "deleted")
    private Integer deleted = 0;
}
