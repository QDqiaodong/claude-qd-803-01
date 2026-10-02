package com.dyeing.mill.controller;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dyeing.mill.common.ApiResponse;
import com.dyeing.mill.common.BizException;
import com.dyeing.mill.domain.ClothCart;
import com.dyeing.mill.domain.CleaningRuleVersion;
import com.dyeing.mill.domain.ScheduleLock;
import com.dyeing.mill.domain.SchedulePlan;
import com.dyeing.mill.domain.VatMaintenance;
import com.dyeing.mill.dto.schedule.CartScheduleInputRequest;
import com.dyeing.mill.dto.schedule.LockRequest;
import com.dyeing.mill.dto.schedule.MaintenanceRequest;
import com.dyeing.mill.dto.schedule.RecomputeRequest;
import com.dyeing.mill.dto.schedule.RuleVersionPublishRequest;
import com.dyeing.mill.dto.schedule.SavePlanRequest;
import com.dyeing.mill.service.schedule.LockService;
import com.dyeing.mill.service.schedule.MaintenanceService;
import com.dyeing.mill.service.schedule.RecomputeManager;
import com.dyeing.mill.service.schedule.RuleVersionService;
import com.dyeing.mill.service.schedule.ScheduleService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 染缸换色排程台接口：
 * 状态总览 / 异步重算与作业状态 / 方案留存选定差异复算 / 规则版本 / 维护 / 锁定 / 布车排程输入。
 */
@RestController
@RequestMapping("/api/schedule")
public class ScheduleController {

    private final ScheduleService scheduleService;
    private final RuleVersionService ruleVersionService;
    private final MaintenanceService maintenanceService;
    private final LockService lockService;
    private final RecomputeManager recomputeManager;
    private final ObjectMapper objectMapper;

    public ScheduleController(ScheduleService scheduleService,
                              RuleVersionService ruleVersionService,
                              MaintenanceService maintenanceService,
                              LockService lockService,
                              RecomputeManager recomputeManager,
                              ObjectMapper objectMapper) {
        this.scheduleService = scheduleService;
        this.ruleVersionService = ruleVersionService;
        this.maintenanceService = maintenanceService;
        this.lockService = lockService;
        this.recomputeManager = recomputeManager;
        this.objectMapper = objectMapper;
    }

    // ============================== 总览与重算 ==============================

    /** 排程台初始化数据：当前规则、输入上下文、方案清单、重算作业状态 */
    @GetMapping("/state")
    public ApiResponse<Map<String, Object>> state() {
        Map<String, Object> data = new LinkedHashMap<>();
        CleaningRuleVersion activeRule = ruleVersionService.active();
        data.put("activeRule", ruleView(activeRule));
        data.put("context", scheduleService.buildContext().getInputSnapshot());
        data.put("plans", scheduleService.listPlans().stream().map(this::planSummary).toList());
        data.put("job", jobView(recomputeManager.status()));
        return ApiResponse.success(data);
    }

    /** 触发异步重算（立即返回作业状态；连续修改被后端合并） */
    @PostMapping("/recompute")
    public ApiResponse<Map<String, Object>> recompute(@RequestBody(required = false) RecomputeRequest request) {
        String operator = request == null ? null : request.getOperator();
        RecomputeManager.Job job = recomputeManager.requestRecompute(operator);
        return ApiResponse.success("重算已提交", jobView(job));
    }

    @GetMapping("/recompute/status")
    public ApiResponse<Map<String, Object>> recomputeStatus() {
        return ApiResponse.success(jobView(recomputeManager.status()));
    }

    private Map<String, Object> jobView(RecomputeManager.Job job) {
        Map<String, Object> view = new LinkedHashMap<>();
        if (job == null) {
            view.put("status", "IDLE");
            return view;
        }
        view.put("id", job.id);
        view.put("status", job.status);
        view.put("operator", job.operator);
        view.put("requestTime", job.requestTime);
        view.put("startTime", job.startTime);
        view.put("finishTime", job.finishTime);
        view.put("draftPlanId", job.draftPlanId);
        view.put("ruleVersionCode", job.ruleVersionCode);
        view.put("fingerprint", job.fingerprint);
        view.put("message", job.message);
        return view;
    }

    // ============================== 方案 ==============================

    /** 方案列表只回轻量摘要，不携带结果/快照/统计三个大 JSON，方案多了也不拖页面 */
    @GetMapping("/plans")
    public ApiResponse<List<Map<String, Object>>> plans() {
        return ApiResponse.success(scheduleService.listPlans().stream()
                .map(this::planSummary).toList());
    }

    private Map<String, Object> planSummary(SchedulePlan p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("planCode", p.getPlanCode());
        m.put("name", p.getName());
        m.put("planKind", p.getPlanKind());
        m.put("status", p.getStatus());
        m.put("ruleVersionId", p.getRuleVersionId());
        m.put("ruleVersionCode", p.getRuleVersionCode());
        m.put("horizonStart", p.getHorizonStart());
        m.put("horizonEnd", p.getHorizonEnd());
        m.put("scheduledCount", p.getScheduledCount());
        m.put("delayedCount", p.getDelayedCount());
        m.put("excludedCount", p.getExcludedCount());
        m.put("lockedCount", p.getLockedCount());
        m.put("createdBy", p.getCreatedBy());
        m.put("createTime", p.getCreateTime());
        m.put("computeMs", p.getComputeMs());
        return m;
    }

    @GetMapping("/plans/draft")
    public ApiResponse<SchedulePlan> draftPlan() {
        return ApiResponse.success(scheduleService.draftPlan());
    }

    @GetMapping("/plans/{id}")
    public ApiResponse<SchedulePlan> planDetail(@PathVariable Long id) {
        return ApiResponse.success(scheduleService.planDetail(id));
    }

    /** 留存当前草案（或指定方案）为不可变快照，可保留多个 */
    @PostMapping("/plans/save")
    public ApiResponse<SchedulePlan> saveSnapshot(@RequestBody SavePlanRequest request) {
        return ApiResponse.success("方案已留存",
                scheduleService.saveSnapshot(request.getSourcePlanId(), request.getName(), request.getOperator()));
    }

    /** 选定一个留存方案作为车间参考（不会改写布车或开缸执行单） */
    @PostMapping("/plans/{id}/reference")
    public ApiResponse<SchedulePlan> markReference(@PathVariable Long id) {
        return ApiResponse.success("已选为车间参考方案", scheduleService.markReference(id));
    }

    @DeleteMapping("/plans/{id}")
    public ApiResponse<Void> deletePlan(@PathVariable Long id) {
        scheduleService.deleteSnapshot(id);
        return ApiResponse.success("方案已删除", null);
    }

    /** 方案差异对比 */
    @GetMapping("/plans/diff")
    public ApiResponse<Map<String, Object>> diff(@RequestParam Long a, @RequestParam Long b) {
        return ApiResponse.success(scheduleService.diff(a, b));
    }

    /** 旧方案按当时规则版本 + 冻结输入复算，返回原口径结果与解释 */
    @PostMapping("/plans/{id}/replay")
    public ApiResponse<Map<String, Object>> replay(@PathVariable Long id) {
        return ApiResponse.success("已按方案存档口径复算", scheduleService.replay(id));
    }

    // ============================== 规则版本 ==============================

    @GetMapping("/rules")
    public ApiResponse<List<CleaningRuleVersion>> rules() {
        return ApiResponse.success(ruleVersionService.list());
    }

    @GetMapping("/rules/active")
    public ApiResponse<Map<String, Object>> activeRule() {
        return ApiResponse.success(ruleView(ruleVersionService.active()));
    }

    @GetMapping("/rules/{id}")
    public ApiResponse<Map<String, Object>> ruleDetail(@PathVariable Long id) {
        return ApiResponse.success(ruleView(ruleVersionService.get(id)));
    }

    /** 保存矩阵为新版本（旧 ACTIVE 自动归档；新方案才采用新口径） */
    @PostMapping("/rules/publish")
    public ApiResponse<CleaningRuleVersion> publishRule(@RequestBody RuleVersionPublishRequest request) {
        CleaningRuleVersion version = ruleVersionService.publish(
                request.getMatrixJson(), request.getFabricMachineJson(),
                request.getParamsJson(), request.getRemark(), request.getOperator());
        return ApiResponse.success("规则新版本 " + version.getVersionCode() + " 已生效（旧方案仍按原版本复算）", version);
    }

    private Map<String, Object> ruleView(CleaningRuleVersion version) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", version.getId());
        view.put("versionCode", version.getVersionCode());
        view.put("status", version.getStatus());
        view.put("remark", version.getRemark());
        view.put("createdBy", version.getCreatedBy());
        view.put("createTime", version.getCreateTime());
        view.put("archivedTime", version.getArchivedTime());
        view.put("matrix", parseJson(version.getMatrixJson()));
        view.put("fabricMachines", parseJson(version.getFabricMachineJson()));
        view.put("params", parseJson(version.getParamsJson()));
        return view;
    }

    private Object parseJson(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            throw new BizException("规则 JSON 无法解析：" + e.getMessage());
        }
    }

    // ============================== 维护时段 ==============================

    @GetMapping("/maintenance")
    public ApiResponse<List<VatMaintenance>> maintenance() {
        return ApiResponse.success(maintenanceService.list());
    }

    @PostMapping("/maintenance")
    public ApiResponse<VatMaintenance> createMaintenance(@RequestBody MaintenanceRequest request) {
        return ApiResponse.success("维护时段已登记", maintenanceService.create(
                request.getVatId(), parseTime(request.getStartTime()), parseTime(request.getEndTime()),
                request.getReason()));
    }

    @PatchMapping("/maintenance/{id}")
    public ApiResponse<VatMaintenance> updateMaintenance(@PathVariable Long id,
                                                         @RequestBody MaintenanceRequest request) {
        return ApiResponse.success("维护时段已更新", maintenanceService.update(
                id, parseTime(request.getStartTime()), parseTime(request.getEndTime()), request.getReason()));
    }

    @DeleteMapping("/maintenance/{id}")
    public ApiResponse<Void> deleteMaintenance(@PathVariable Long id) {
        maintenanceService.delete(id);
        return ApiResponse.success("维护时段已删除", null);
    }

    // ============================== 人工锁定 ==============================

    @GetMapping("/locks")
    public ApiResponse<List<ScheduleLock>> locks() {
        return ApiResponse.success(lockService.list());
    }

    @PostMapping("/locks")
    public ApiResponse<ScheduleLock> lock(@RequestBody LockRequest request) {
        return ApiResponse.success("已锁定承诺段，重算将绕开该时段",
                lockService.lock(request.getCartId(), request.getVatId(),
                        parseTime(request.getStartTime()), parseTime(request.getEndTime()),
                        request.getNote(), request.getOperator()));
    }

    @DeleteMapping("/locks/{id}")
    public ApiResponse<Void> unlock(@PathVariable Long id) {
        lockService.unlock(id);
        return ApiResponse.success("锁定段已解除", null);
    }

    // ============================== 布车排程输入 ==============================

    /** 仅修改交期/急单两个排程输入；布车状态、缸位、执行单都不受影响 */
    @PatchMapping("/carts/{id}/schedule-input")
    public ApiResponse<ClothCart> updateCartInput(@PathVariable Long id,
                                                  @RequestBody CartScheduleInputRequest request) {
        LocalDateTime due = "".equals(request.getDueTime()) ? null
                : (request.getDueTime() == null ? null : parseTime(request.getDueTime()));
        return ApiResponse.success("排程输入已更新（布车状态未改动）",
                scheduleService.updateCartScheduleInput(id, due, request.getUrgent()));
    }

    private LocalDateTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            throw new BizException("时间不能为空");
        }
        try {
            return LocalDateTime.parse(value.replace(" ", "T"));
        } catch (DateTimeParseException e) {
            throw new BizException("时间格式有误，应为 yyyy-MM-dd HH:mm：" + value);
        }
    }
}
