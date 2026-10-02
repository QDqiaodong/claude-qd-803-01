package com.dyeing.mill.dto.schedule;

import lombok.Data;

/** 维护时段新增/编辑 */
@Data
public class MaintenanceRequest {
    private Long vatId;
    private String startTime;
    private String endTime;
    private String reason;
}
