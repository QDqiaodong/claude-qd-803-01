package com.dyeing.mill.dto.schedule;

import lombok.Data;

/** 留存方案请求 */
@Data
public class SavePlanRequest {
    private Long sourcePlanId;
    private String name;
    private String operator;
}
