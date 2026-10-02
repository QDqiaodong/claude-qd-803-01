package com.dyeing.mill.dto.schedule;

import lombok.Data;

/** 保存清洗规则新版本请求（三段 JSON 字符串由前端表单对象 JSON.stringify 提交） */
@Data
public class RuleVersionPublishRequest {
    private String matrixJson;
    private String fabricMachineJson;
    private String paramsJson;
    private String remark;
    private String operator;
}
