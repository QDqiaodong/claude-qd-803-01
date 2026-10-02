package com.dyeing.mill.dto.schedule;

import lombok.Data;

/** 人工锁定段请求 */
@Data
public class LockRequest {
    private Long cartId;
    private Long vatId;
    private String startTime;
    private String endTime;
    private String note;
    private String operator;
}
