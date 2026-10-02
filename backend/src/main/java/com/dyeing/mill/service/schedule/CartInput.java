package com.dyeing.mill.service.schedule;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Data;

/** 排程输入：一辆待排布车（交期、急单、折算重量、资料完备性由服务层填好） */
@Data
public class CartInput {
    private Long id;
    private String cartCode;
    private String fabricType;
    private String colorCode;
    private String colorName;
    private String colorHex;
    private String customer;
    private BigDecimal meters;
    private BigDecimal weightKg;
    private LocalDateTime dueTime;
    private boolean urgent;
    private int reworkTimes;

    /** 资料解析结果（服务层预校验） */
    private boolean recipeOk;
    private Long recipeId;
    private String recipeCode;
    private Integer recipeVersionNo;
    private boolean curveOk;
    private Long curveId;
    private String curveCode;
    /** 缸内作业时长（曲线总时长，分钟） */
    private int processMinutes;
    private String recipeIssue;
    private String curveIssue;
}
