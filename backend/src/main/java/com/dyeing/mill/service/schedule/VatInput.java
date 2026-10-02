package com.dyeing.mill.service.schedule;

import java.math.BigDecimal;

import lombok.Data;

/** 排程输入：一台染缸 */
@Data
public class VatInput {
    private Long id;
    private String vatCode;
    private String machineType;
    private BigDecimal capacityKg;
    private String workshop;
}
