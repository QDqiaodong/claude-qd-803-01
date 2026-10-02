package com.dyeing.mill.dto.schedule;

import lombok.Data;

/** 修改布车排程输入（只动交期/急单，不动布车状态） */
@Data
public class CartScheduleInputRequest {
    /** yyyy-MM-dd HH:mm，传空串可清空交期 */
    private String dueTime;
    /** 0/1 */
    private Integer urgent;
}
