package com.dyeing.mill.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dyeing.mill.common.BizException;
import com.dyeing.mill.domain.SchedulePlan;
import com.dyeing.mill.domain.WashRuleVersion;
import com.dyeing.mill.dto.ScheduleGenerateRequest;
import com.dyeing.mill.engine.ScheduleEngine;
import com.dyeing.mill.engine.ScheduleInput;
import com.dyeing.mill.engine.ScheduleOutput;
import com.dyeing.mill.repository.ColorFamilyRepository;
import com.dyeing.mill.repository.MaintenanceWindowRepository;
import com.dyeing.mill.repository.ScheduleLockRepository;
import com.dyeing.mill.repository.SchedulePlanAssignmentRepository;
import com.dyeing.mill.repository.SchedulePlanRepository;
import com.dyeing.mill.repository.WashRuleRepository;
import com.dyeing.mill.repository.WashRuleVersionRepository;

/**
 * 换色排程方案服务：
 *  - 生成在后台线程执行（批量重算不阻塞页面），并对“相同输入指纹”的并发请求做合并（去重）；
 *  - 方案只存排程结果与输入快照，绝不回写布车/染缸，也不开执行单；
 *  - 复算（replay）按方案保存的规则版本 + 输入快照重跑引擎，得到当时口径的解释。
 */
@Service
public class SchedulePlanService {

    private final SchedulePlanRepository planRepository;
    private final SchedulePlanAssignmentRepository assignmentRepository;
    private final WashRuleRepository ruleRepository;
    private final WashRuleVersionRepository ruleVersionRepository;
    private final WashRuleService washRuleService;
    private final ColorFamilyRepository familyRepository;
    private final MaintenanceWindowRepository maintenanceRepository;
    private final ScheduleLockRepository lockRepository;
    private final ScheduleInputAssembler assembler;
    private final ScheduleTxBean txBean;

    /** 进行中的重算作业：scopeKey（输入指纹）-> job，相同指纹直接复用，连续修改只重算一次最新口径 */
    private final Map<String, Job> runningJobs = new ConcurrentHashMap<>();
    /** 已完成作业短期保留，防止“计算快于首次轮询”时页面查不到结果 */
    private final Map<Long, Job> finishedJobs = new ConcurrentHashMap<>();
    private final AtomicLong jobSeq = new AtomicLong(1);

    /** 单线程重算池：批量重算串行执行，避免把页面/数据库拖到长时间无响应 */
    private final ExecutorService recomputePool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "schedule-recompute");
        t.setDaemon(true);
        return t;
    });

    public SchedulePlanService(SchedulePlanRepository planRepository,
                               SchedulePlanAssignmentRepository assignmentRepository,
                               WashRuleRepository ruleRepository,
                               WashRuleVersionRepository ruleVersionRepository,
                               WashRuleService washRuleService,
                               ColorFamilyRepository familyRepository,
                               MaintenanceWindowRepository maintenanceRepository,
                               ScheduleLockRepository lockRepository,
                               ScheduleInputAssembler assembler,
                               ScheduleTxBean txBean) {
        this.planRepository = planRepository;
        this.assignmentRepository = assignmentRepository;
        this.ruleRepository = ruleRepository;
        this.ruleVersionRepository = ruleVersionRepository;
        this.washRuleService = washRuleService;
        this.familyRepository = familyRepository;
        this.maintenanceRepository = maintenanceRepository;
        this.lockRepository = lockRepository;
        this.assembler = assembler;
        this.txBean = txBean;
    }

    // ----------------------------------------------------------------
    // 异步重算作业
    // ----------------------------------------------------------------

    /**
     * 发起重算。相同输入指纹的并发/连续请求合并到同一个作业（去重）；
     * 前端只认最新 jobId，轮询拿到的最终结果必然对应最新输入。
     */
    public JobSubmit submitGenerate(ScheduleGenerateRequest request) {
        int horizonHours = request.getHorizonHours() == null || request.getHorizonHours() <= 0
                ? 120 : Math.min(request.getHorizonHours(), 24 * 30);
        LocalDateTime anchor = request.getAnchorTime() == null || request.getAnchorTime().isBlank()
                ? LocalDateTime.now().withMinute(0).withSecond(0).withNano(0)
                : parseTime(request.getAnchorTime());
        String scopeKey = "live|" + horizonHours + "|" + anchor;

        Job[] created = new Job[1];
        Job job = runningJobs.computeIfAbsent(scopeKey, k -> {
            Job j = new Job();
            j.jobId = jobSeq.getAndIncrement();
            j.scopeKey = k;
            j.planName = request.getPlanName();
            j.createdBy = request.getCreatedBy();
            j.status = "RUNNING";
            j.submitTime = LocalDateTime.now();
            created[0] = j;
            recomputePool.submit(() -> runGenerate(j, horizonHours, anchor));
            return j;
        });
        JobSubmit submit = new JobSubmit();
        submit.jobId = job.jobId;
        submit.status = job.status;
        submit.merged = created[0] == null; // 已有同指纹作业：本次请求被合并，不产生重复计算
        return submit;
    }

    public Job getJob(long jobId) {
        Job running = runningJobs.values().stream().filter(j -> j.jobId == jobId).findFirst().orElse(null);
        if (running != null) {
            return running;
        }
        Job finished = finishedJobs.get(jobId);
        if (finished != null && finished.finishTime != null
                && finished.finishTime.isBefore(LocalDateTime.now().minusMinutes(10))) {
            finishedJobs.remove(jobId);
            return null;
        }
        return finished;
    }

    /** 后台线程执行：只读取数 → 引擎计算 → 独立事务落库（均经跨 Bean 调用，事务生效）。 */
    void runGenerate(Job job, int horizonHours, LocalDateTime anchor) {
        try {
            GenerateBundle bundle = txBean.loadLiveBundle(horizonHours, anchor);
            ScheduleEngine engine = new ScheduleEngine(bundle.input.getFamilies(), bundle.input.getWashCells());
            ScheduleOutput output = engine.solve(bundle.input);
            SchedulePlan plan = txBean.persistPlan(job.planName, job.createdBy, bundle, output, "NOW");
            job.planId = plan.getId();
            job.planCode = plan.getPlanCode();
            job.status = "DONE";
        } catch (Exception e) {
            job.status = "FAILED";
            job.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        } finally {
            job.finishTime = LocalDateTime.now();
            runningJobs.remove(job.scopeKey);
            finishedJobs.put(job.jobId, job);
        }
    }

    /** 事务落库统一委托 ScheduleTxBean（后台线程内自调用不会开启事务）。 */
    public SchedulePlan persistPlan(String planName, String createdBy, GenerateBundle bundle,
                                    ScheduleOutput output, String mode) {
        return txBean.persistPlan(planName, createdBy, bundle, output, mode);
    }

    // ----------------------------------------------------------------
    // 方案列表 / 详情 / 留存（选定参考）
    // ----------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<SchedulePlan> listPlans() {
        return planRepository.findAllOrdered();
    }

    @Transactional(readOnly = true)
    public SchedulePlan detail(Long id) {
        SchedulePlan plan = planRepository.findById(id)
                .orElseThrow(() -> new BizException("排程方案不存在：id=" + id));
        if (plan.getAssignments().isEmpty()) {
            plan.getAssignments().addAll(assignmentRepository.findByPlanId(id));
        }
        return plan;
    }

    /** 方案完整视图：时间轴、染缸负载、清洗占时、解释、口径版本都从这里取。 */
    @Transactional(readOnly = true)
    public Map<String, Object> planView(Long id) {
        SchedulePlan plan = detail(id);
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("plan", planSummary(plan));
        data.put("assignments", plan.getAssignments());
        data.put("maintenance", maintenanceRepository.findAllOrdered());
        data.put("locks", lockRepository.findAllOrdered());
        data.put("families", familyRepository.findAllOrdered());
        data.put("ruleVersion", washRuleService.versionView(washRuleService.getVersion(plan.getRuleVersionId())));
        return data;
    }

    public Map<String, Object> planSummary(SchedulePlan plan) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", plan.getId());
        m.put("planCode", plan.getPlanCode());
        m.put("planName", plan.getPlanName());
        m.put("status", plan.getStatus());
        m.put("ruleVersionId", plan.getRuleVersionId());
        m.put("ruleVersionNo", plan.getRuleVersionNo());
        m.put("anchorMode", plan.getAnchorMode());
        m.put("horizonStartTime", plan.getHorizonStartTime());
        m.put("horizonEndTime", plan.getHorizonEndTime());
        m.put("totalCarts", plan.getTotalCarts());
        m.put("scheduledCarts", plan.getScheduledCarts());
        m.put("excludedCarts", plan.getExcludedCarts());
        m.put("lateCarts", plan.getLateCarts());
        m.put("totalWashMinutes", plan.getTotalWashMinutes());
        m.put("totalWashCost", plan.getTotalWashCost());
        m.put("totalIdleMinutes", plan.getTotalIdleMinutes());
        m.put("totalLateMinutes", plan.getTotalLateMinutes());
        m.put("createdBy", plan.getCreatedBy());
        m.put("createTime", plan.getCreateTime());
        m.put("referenceTime", plan.getReferenceTime());
        return m;
    }

    /**
     * 选定为车间参考：同表仅一个 REFERENCE（生成列唯一索引兜底），旧参考自动降为 DRAFT。
     * 只是标注，不回写布车或开缸执行单。
     */
    @Transactional
    public SchedulePlan markReference(Long id) {
        SchedulePlan plan = detail(id);
        Optional<SchedulePlan> current = planRepository.findReference();
        current.ifPresent(p -> {
            p.setStatus("DRAFT");
            p.setReferenceTime(null);
            planRepository.save(p);
        });
        plan.setStatus("REFERENCE");
        plan.setReferenceTime(LocalDateTime.now());
        return planRepository.save(plan);
    }

    @Transactional
    public SchedulePlan rename(Long id, String name) {
        SchedulePlan plan = detail(id);
        if (name == null || name.isBlank()) {
            throw new BizException("方案名称不能为空");
        }
        plan.setPlanName(name.trim().length() > 64 ? name.trim().substring(0, 64) : name.trim());
        return planRepository.save(plan);
    }

    // ----------------------------------------------------------------
    // 版本兼容复算：方案当时的规则版本 + 输入快照重跑，得到原口径解释
    // ----------------------------------------------------------------

    @Transactional
    public SchedulePlan replay(Long id, String createdBy) {
        SchedulePlan plan = detail(id);
        WashRuleVersion rule = ruleVersionRepository.findById(plan.getRuleVersionId())
                .orElseThrow(() -> new BizException("方案使用的规则版本已不存在，无法复算"));
        rule.setRules(ruleRepository.findByVersionIdOrderByIdAsc(rule.getId()));
        ScheduleInput input = assembler.fromSnapshot(plan.getInputSnapshotJson(), rule);
        ScheduleEngine engine = new ScheduleEngine(input.getFamilies(), input.getWashCells());
        ScheduleOutput output = engine.solve(input);

        GenerateBundle bundle = new GenerateBundle();
        bundle.input = input;
        bundle.rule = rule;
        return persistPlan(plan.getPlanName() + "（复算 V" + rule.getVersionNo() + "）",
                createdBy, bundle, output, "REPLAY");
    }

    private LocalDateTime parseTime(String value) {
        try {
            return LocalDateTime.parse(value.replace(" ", "T"));
        } catch (DateTimeParseException e) {
            throw new BizException("时间格式有误，应为 yyyy-MM-dd HH:mm");
        }
    }

    // ---- 内部传输结构 ----
    public static class GenerateBundle {
        public ScheduleInput input;
        public WashRuleVersion rule;
    }

    /** 重算作业（页面轮询用） */
    public static class Job {
        public long jobId;
        public String scopeKey;
        public String planName;
        public String createdBy;
        public volatile String status;
        public volatile Long planId;
        public volatile String planCode;
        public volatile String error;
        public volatile LocalDateTime submitTime;
        public volatile LocalDateTime finishTime;
    }

    public static class JobSubmit {
        public long jobId;
        public String status;
        /** 是否与进行中的作业合并（连续修改只重算最新一次） */
        public boolean merged;
    }
}
