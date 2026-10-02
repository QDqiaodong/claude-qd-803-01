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
 * 换色清洗规则版本：颜色族/深浅档矩阵、布种→机型适配、排程参数整体版本化。
 * ACTIVE 唯一（业务层保证）；归档版本不可变，旧方案按当时版本复算并显示原解释。
 */
@Data
@Entity
@Table(name = "cleaning_rule_version")
public class CleaningRuleVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "version_code", nullable = false, length = 32)
    private String versionCode;

    /** ACTIVE 当前生效 / ARCHIVED 历史归档 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 颜色族 + 四类切换清洗矩阵 JSON（分类默认值 + 逐族覆写） */
    @Lob
    @Column(name = "matrix_json", nullable = false)
    private String matrixJson;

    /** 布种适配机机型矩阵 JSON */
    @Lob
    @Column(name = "fabric_machine_json", nullable = false)
    private String fabricMachineJson;

    /** 排程参数 JSON（逾期/空档/清洗权重、米重折算、时槽粒度、排程视野） */
    @Lob
    @Column(name = "params_json", nullable = false)
    private String paramsJson;

    @Column(name = "remark", length = 200)
    private String remark;

    @Column(name = "created_by", length = 32)
    private String createdBy;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "archived_time")
    private LocalDateTime archivedTime;
}
