package com.dyeing.mill.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 染缸换色排程方案：
 * planKind=DRAFT 为随重算覆盖的工作草案（唯一），SNAPSHOT 为人工留存的历史方案（不可变）；
 * status=REFERANCE 表示被选为车间参考（全局至多一个），方案本身不改写布车或开缸执行单。
 * 输入快照 + 规则版本号保证旧方案可按当时版本复算并显示原解释。
 */
@Data
@Entity
@Table(name = "schedule_plan")
public class SchedulePlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_code", nullable = false, length = 40)
    private String planCode;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    /** DRAFT 工作草案 / SNAPSHOT 留存快照 */
    @Column(name = "plan_kind", nullable = false, length = 16)
    private String planKind;

    /** REFERENCE 车间参考 / ACTIVE 普通 / ARCHIVED 已弃用 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "rule_version_id", nullable = false)
    private Long ruleVersionId;

    @Column(name = "rule_version_code", nullable = false, length = 32)
    private String ruleVersionCode;

    @Column(name = "horizon_start", nullable = false)
    private LocalDateTime horizonStart;

    @Column(name = "horizon_end", nullable = false)
    private LocalDateTime horizonEnd;

    /** 排程时输入冻结快照（待排布车/染缸/维护/锁定/交期/急单） */
    @Lob
    @Column(name = "input_snapshot_json", nullable = false)
    private String inputSnapshotJson;

    /** 排程结果（时间轴条目、清洗段、每车解释、排除原因） */
    @Lob
    @Column(name = "result_json", nullable = false)
    private String resultJson;

    /** 汇总指标（逾期分钟、空档分钟、清洗分钟/成本、负载） */
    @Lob
    @Column(name = "stats_json", nullable = false)
    private String statsJson;

    /** 输入指纹：异步重算只提交与最新输入一致的结果 */
    @Column(name = "input_fingerprint", nullable = false, length = 64)
    private String inputFingerprint;

    @Column(name = "scheduled_count", nullable = false)
    private Integer scheduledCount = 0;

    @Column(name = "delayed_count", nullable = false)
    private Integer delayedCount = 0;

    @Column(name = "excluded_count", nullable = false)
    private Integer excludedCount = 0;

    @Column(name = "locked_count", nullable = false)
    private Integer lockedCount = 0;

    @Column(name = "created_by", length = 32)
    private String createdBy;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "compute_ms")
    private Long computeMs = 0L;

    @Column(name = "deleted")
    private Integer deleted = 0;
}
