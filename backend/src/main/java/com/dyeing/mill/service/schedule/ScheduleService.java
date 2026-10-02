package com.dyeing.mill.service.schedule;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dyeing.mill.common.BizException;
import com.dyeing.mill.domain.ClothCart;
import com.dyeing.mill.domain.CleaningRuleVersion;
import com.dyeing.mill.domain.DyeVat;
import com.dyeing.mill.domain.ProcessCurve;
import com.dyeing.mill.domain.Recipe;
import com.dyeing.mill.domain.ScheduleLock;
import com.dyeing.mill.domain.SchedulePlan;
import com.dyeing.mill.domain.VatMaintenance;
import com.dyeing.mill.repository.ClothCartRepository;
import com.dyeing.mill.repository.CleaningRuleVersionRepository;
import com.dyeing.mill.repository.DyeVatRepository;
import com.dyeing.mill.repository.ProcessCurveRepository;
import com.dyeing.mill.repository.RecipeRepository;
import com.dyeing.mill.repository.ScheduleLockRepository;
import com.dyeing.mill.repository.SchedulePlanRepository;
import com.dyeing.mill.repository.VatMaintenanceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Data;

/**
 * 换色排程应用服务：组装输入快照与指纹、调用 {@link SchedulingEngine}、持久化方案、
 * 提供方案留存/选定参考/差异对比/旧版本复算。
 *
 * 关键边界：排程台只读本模块与基础台账，写操作仅限本模块四张表 + 布车交期/急单两个排程输入字段；
 * 绝不修改布车状态、缸位或开缸执行单。
 */
@Service
public class ScheduleService {

    private final ClothCartRepository cartRepository;
    private final DyeVatRepository vatRepository;
    private final RecipeRepository recipeRepository;
    private final ProcessCurveRepository curveRepository;
    private final CleaningRuleVersionRepository ruleRepository;
    private final VatMaintenanceRepository maintenanceRepository;
    private final ScheduleLockRepository lockRepository;
    private final SchedulePlanRepository planRepository;
    private final RuleVersionService ruleVersionService;
    private final ObjectMapper objectMapper;

    public ScheduleService(ClothCartRepository cartRepository,
                           DyeVatRepository vatRepository,
                           RecipeRepository recipeRepository,
                           ProcessCurveRepository curveRepository,
                           CleaningRuleVersionRepository ruleRepository,
                           VatMaintenanceRepository maintenanceRepository,
                           ScheduleLockRepository lockRepository,
                           SchedulePlanRepository planRepository,
                           RuleVersionService ruleVersionService,
                           ObjectMapper objectMapper) {
        this.cartRepository = cartRepository;
        this.vatRepository = vatRepository;
        this.recipeRepository = recipeRepository;
        this.curveRepository = curveRepository;
        this.ruleRepository = ruleRepository;
        this.maintenanceRepository = maintenanceRepository;
        this.lockRepository = lockRepository;
        this.planRepository = planRepository;
        this.ruleVersionService = ruleVersionService;
        this.objectMapper = objectMapper;
    }

    // ============================== 输入上下文 ==============================

    /**
     * 构建当前排程上下文：待排候选车（PENDING/REWORK）、全部缸、维护、锁定、当前规则。
     * 输入快照与指纹都从这里产生，保证「页面看到的」「参与计算的」「写入方案的」三者同源。
     */
    @Transactional(readOnly = true)
    public ScheduleContext buildContext() {
        CleaningRuleVersion activeRule = ruleVersionService.active();
        return buildContext(activeRule.getId());
    }

    @Transactional(readOnly = true)
    public ScheduleContext buildContext(Long ruleVersionId) {
        CleaningRuleVersion ruleVersion = ruleVersionId == null
                ? ruleVersionService.active() : ruleVersionService.get(ruleVersionId);
        RuleSet ruleSet = ruleVersionService.loadRuleSet(ruleVersion.getId());

        List<DyeVat> vats = vatRepository.findAll();
        List<VatMaintenance> maintenance = maintenanceRepository.findAllActive();
        List<ScheduleLock> locks = lockRepository.findAllActive();
        List<ClothCart> allCarts = cartRepository.findAll();

        List<CartInput> candidates = new ArrayList<>();
        List<Map<String, Object>> cartSnapshots = new ArrayList<>();
        for (ClothCart cart : allCarts) {
            String status = cart.getStatus() == null ? "PENDING" : cart.getStatus();
            if (!"PENDING".equals(status) && !"REWORK".equals(status)) {
                continue;
            }
            CartInput input = toCartInput(cart, ruleSet);
            candidates.add(input);
            cartSnapshots.add(cartSnapshot(cart, input));
        }

        List<VatInput> vatInputs = new ArrayList<>();
        List<Map<String, Object>> vatSnapshots = new ArrayList<>();
        for (DyeVat vat : vats) {
            VatInput vi = new VatInput();
            vi.setId(vat.getId());
            vi.setVatCode(vat.getVatCode());
            vi.setMachineType(vat.getMachineType());
            vi.setCapacityKg(vat.getCapacityKg());
            vi.setWorkshop(vat.getWorkshop());
            vatInputs.add(vi);

            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("id", vat.getId());
            snap.put("vatCode", vat.getVatCode());
            snap.put("machineType", vat.getMachineType());
            snap.put("capacityKg", vat.getCapacityKg());
            snap.put("workshop", vat.getWorkshop());
            vatSnapshots.add(snap);
        }

        LocalDateTime horizonStart = parseHorizonStart(ruleSet.getParams().getHorizonStart());
        LocalDateTime horizonEnd = horizonStart.plusDays(ruleSet.getParams().getHorizonDays());

        List<SchedulingEngine.BlockInput> maintenanceBlocks = new ArrayList<>();
        List<Map<String, Object>> maintenanceSnapshots = new ArrayList<>();
        for (VatMaintenance m : maintenance) {
            maintenanceBlocks.add(toBlock(m.getVatId(), null, m.getStartTime(), m.getEndTime(),
                    m.getVatCode(), m.getReason()));
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("id", m.getId());
            snap.put("vatId", m.getVatId());
            snap.put("vatCode", m.getVatCode());
            snap.put("startTime", m.getStartTime());
            snap.put("endTime", m.getEndTime());
            snap.put("reason", m.getReason());
            maintenanceSnapshots.add(snap);
        }

        List<SchedulingEngine.BlockInput> lockBlocks = new ArrayList<>();
        List<Map<String, Object>> lockSnapshots = new ArrayList<>();
        for (ScheduleLock lock : locks) {
            ClothCart lockedCart = allCarts.stream()
                    .filter(c -> Objects.equals(c.getId(), lock.getCartId())).findFirst().orElse(null);
            String colorCode = lockedCart == null ? null : lockedCart.getColorCode();
            String colorHex = lockedCart == null ? null : recipeRepository.findAll().stream()
                    .filter(r -> Objects.equals(r.getColorCode(), lockedCart.getColorCode()))
                    .map(Recipe::getColorHex).findFirst().orElse(null);
            SchedulingEngine.BlockInput block = toBlock(lock.getVatId(), lock.getCartId(),
                    lock.getStartTime(), lock.getEndTime(), lock.getCartCode(), lock.getNote());
            block.setCartCode(lock.getCartCode());
            block.setColorCode(colorCode);
            block.setColorHex(colorHex);
            block.setUrgent(lockedCart != null && lockedCart.getUrgent() != null && lockedCart.getUrgent() == 1);
            lockBlocks.add(block);
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("id", lock.getId());
            snap.put("cartId", lock.getCartId());
            snap.put("cartCode", lock.getCartCode());
            snap.put("vatId", lock.getVatId());
            snap.put("vatCode", lock.getVatCode());
            snap.put("startTime", lock.getStartTime());
            snap.put("endTime", lock.getEndTime());
            snap.put("note", lock.getNote());
            snap.put("colorCode", colorCode);
            snap.put("colorHex", colorHex);
            snap.put("urgent", block.isUrgent());
            snap.put("ruleVersionId", lock.getRuleVersionId());
            lockSnapshots.add(snap);
        }

        ScheduleContext context = new ScheduleContext();
        context.setRuleVersion(ruleVersion);
        context.setRuleSet(ruleSet);
        context.setVats(vatInputs);
        context.setCarts(candidates);
        context.setMaintenance(maintenanceBlocks);
        context.setLocks(lockBlocks);
        context.setHorizonStart(horizonStart);
        context.setHorizonEnd(horizonEnd);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("horizonStart", horizonStart);
        snapshot.put("horizonEnd", horizonEnd);
        snapshot.put("carts", cartSnapshots);
        snapshot.put("vats", vatSnapshots);
        snapshot.put("maintenance", maintenanceSnapshots);
        snapshot.put("locks", lockSnapshots);
        snapshot.put("ruleVersionId", ruleVersion.getId());
        snapshot.put("ruleVersionCode", ruleVersion.getVersionCode());
        context.setInputSnapshot(snapshot);
        context.setFingerprint(fingerprint(snapshot));
        return context;
    }

    private CartInput toCartInput(ClothCart cart, RuleSet ruleSet) {
        CartInput input = new CartInput();
        input.setId(cart.getId());
        input.setCartCode(cart.getCartCode());
        input.setFabricType(cart.getFabricType());
        input.setColorCode(cart.getColorCode());
        input.setColorName(cart.getColorName());
        input.setCustomer(cart.getCustomer());
        input.setColorHex(recipeRepository.findAll().stream()
                .filter(r -> Objects.equals(r.getColorCode(), cart.getColorCode()))
                .map(Recipe::getColorHex).findFirst().orElse(null));
        input.setMeters(cart.getMeters());
        input.setDueTime(cart.getDueTime());
        input.setUrgent(cart.getUrgent() != null && cart.getUrgent() == 1);
        input.setReworkTimes(cart.getReworkTimes() == null ? 0 : cart.getReworkTimes());

        BigDecimal weight = cart.getMeters() == null ? null
                : cart.getMeters().multiply(ruleSet.getParams().getMetersToKg())
                        .setScale(1, RoundingMode.HALF_UP);
        input.setWeightKg(weight);

        // 资料完备性：已发布、布种色号双匹配的最新配方；绑定该配方且布种色号匹配的曲线
        List<Recipe> released = recipeRepository.findAll().stream()
                .filter(r -> "RELEASED".equals(r.getStatus()))
                .filter(r -> Objects.equals(r.getColorCode(), cart.getColorCode()))
                .filter(r -> Objects.equals(r.getFabricType(), cart.getFabricType()))
                .sorted(Comparator.comparing(Recipe::getVersionNo,
                        Comparator.nullsLast(Integer::compareTo)).reversed())
                .toList();
        if (released.isEmpty()) {
            input.setRecipeOk(false);
            input.setRecipeIssue("色号 " + cart.getColorCode() + "（" + cart.getColorName()
                    + "）+ 布种 " + cart.getFabricType() + " 没有状态为「已发布」的配方，资料不完整不能排程");
        } else {
            Recipe recipe = released.get(0);
            input.setRecipeOk(true);
            input.setRecipeId(recipe.getId());
            input.setRecipeCode(recipe.getRecipeCode());
            input.setRecipeVersionNo(recipe.getVersionNo());
            List<ProcessCurve> curves = curveRepository.findAll().stream()
                    .filter(c -> Objects.equals(c.getFabricType(), cart.getFabricType()))
                    .filter(c -> Objects.equals(c.getColorCode(), cart.getColorCode()))
                    .filter(c -> Objects.equals(c.getRecipeId(), recipe.getId()))
                    .toList();
            if (curves.isEmpty()) {
                input.setCurveOk(false);
                input.setCurveIssue("已发布配方 " + recipe.getRecipeCode()
                        + " 没有绑定同布种同色号的工艺曲线，作业时长无法确定");
            } else {
                ProcessCurve curve = curves.get(0);
                input.setCurveOk(true);
                input.setCurveId(curve.getId());
                input.setCurveCode(curve.getCurveCode());
                input.setProcessMinutes(curve.getTotalMinutes() == null ? 0 : curve.getTotalMinutes());
            }
        }
        return input;
    }

    private Map<String, Object> cartSnapshot(ClothCart cart, CartInput input) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("id", cart.getId());
        snap.put("cartCode", cart.getCartCode());
        snap.put("fabricType", cart.getFabricType());
        snap.put("colorCode", cart.getColorCode());
        snap.put("colorName", cart.getColorName());
        snap.put("customer", cart.getCustomer());
        snap.put("meters", cart.getMeters());
        snap.put("weightKg", input.getWeightKg());
        snap.put("dueTime", cart.getDueTime());
        snap.put("urgent", input.isUrgent());
        snap.put("reworkTimes", input.getReworkTimes());
        snap.put("recipeId", input.getRecipeId());
        snap.put("recipeCode", input.getRecipeCode());
        snap.put("recipeVersionNo", input.getRecipeVersionNo());
        snap.put("recipeOk", input.isRecipeOk());
        snap.put("curveId", input.getCurveId());
        snap.put("curveCode", input.getCurveCode());
        snap.put("curveOk", input.isCurveOk());
        snap.put("processMinutes", input.getProcessMinutes());
        return snap;
    }

    private SchedulingEngine.BlockInput toBlock(Long vatId, Long cartId, LocalDateTime start,
                                                LocalDateTime end, String label, String note) {
        SchedulingEngine.BlockInput block = new SchedulingEngine.BlockInput();
        block.setVatId(vatId);
        block.setCartId(cartId);
        block.setStart(start);
        block.setEnd(end);
        block.setLabel(label);
        block.setNote(note);
        return block;
    }

    private LocalDateTime parseHorizonStart(String value) {
        if (value == null || value.isBlank()) {
            return LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusHours(1);
        }
        try {
            return LocalDateTime.parse(value.replace(" ", "T"));
        } catch (Exception e) {
            throw new BizException("排程起点格式有误：" + value);
        }
    }

    // ============================== 计算与方案落库 ==============================

    /** 执行计算并返回结果对象（同步；异步排队由 RecomputeManager 包装） */
    public ComputedPlan compute(ScheduleContext context) {
        SchedulingEngine engine = new SchedulingEngine(
                context.getRuleSet(), context.getHorizonStart(), context.getHorizonEnd());
        engine.bindVats(context.getVats());
        SchedulingEngine.ScheduleResult result = engine.solve(
                context.getCarts(), context.getMaintenance(), context.getLocks());

        // 锁定车的缸位/时段/解释回填（引擎只拿到了块，解释需要锁自身信息）
        fillLockDetails(result, context);

        Map<String, Object> stats = buildStats(result);
        ComputedPlan computed = new ComputedPlan();
        computed.setResult(result);
        computed.setStats(stats);
        computed.setFingerprint(context.getFingerprint());
        computed.setInputSnapshot(context.getInputSnapshot());
        computed.setContext(context);
        return computed;
    }

    /**
     * 引擎已基于锁定块填好缸位/时段/解释；这里只补甘特图锁定块的车号与色名（色值已由块带入）。
     */
    private void fillLockDetails(SchedulingEngine.ScheduleResult result, ScheduleContext context) {
        Map<Long, CartInput> cartById = new LinkedHashMap<>();
        context.getCarts().forEach(c -> cartById.put(c.getId(), c));
        for (SchedulingEngine.TimelineItem item : result.getTimeline()) {
            if (!"LOCK".equals(item.getKind())) {
                continue;
            }
            SchedulingEngine.BlockInput lock = context.getLocks().stream()
                    .filter(l -> Objects.equals(l.getVatId(), item.getVatId())
                            && Objects.equals(l.getStart(), item.getStart())
                            && Objects.equals(l.getEnd(), item.getEnd()))
                    .findFirst().orElse(null);
            if (lock == null) {
                continue;
            }
            item.setCartCode(lock.getCartCode());
            item.setColorCode(lock.getColorCode());
            item.setColorHex(lock.getColorHex());
            item.setUrgent(lock.isUrgent());
            CartInput cart = cartById.get(lock.getCartId());
            if (cart != null) {
                item.setColorName(cart.getColorName());
            }
        }
    }

    private Map<String, Object> buildStats(SchedulingEngine.ScheduleResult result) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalCarts", result.getTotalCarts());
        stats.put("scheduledCount", result.getScheduledCount());
        stats.put("lockedCount", result.getLockedCount());
        stats.put("delayedCount", result.getDelayedCount());
        stats.put("excludedCount", result.getExcludedCount());
        stats.put("lateCount", result.getLateCount());
        stats.put("totalLateMinutes", result.getTotalLateMinutes());
        stats.put("totalWashMinutes", result.getTotalWashMinutes());
        stats.put("totalWashCost", result.getTotalWashCost());
        stats.put("totalIdleMinutes", result.getTotalIdleMinutes());
        stats.put("totalBusyMinutes", result.getTotalBusyMinutes());
        stats.put("computeMs", result.getComputeMs());
        return stats;
    }

    /** 把计算结果覆盖写入工作草案（DRAFT 唯一），返回草案 */
    @Transactional
    public SchedulePlan upsertDraft(ComputedPlan computed) {
        ScheduleContext context = computed.getContext();
        SchedulePlan draft = planRepository.findDrafts().stream().findFirst().orElseGet(() -> {
            SchedulePlan p = new SchedulePlan();
            p.setPlanKind("DRAFT");
            return p;
        });
        String jsonResult = writeJson(computed.getResult());
        String jsonStats = writeJson(computed.getStats());
        draft.setPlanCode(draft.getPlanCode() == null ? generatePlanCode("DR") : draft.getPlanCode());
        draft.setName("工作草案（最新输入）");
        draft.setStatus("ACTIVE");
        draft.setRuleVersionId(context.getRuleVersion().getId());
        draft.setRuleVersionCode(context.getRuleVersion().getVersionCode());
        draft.setHorizonStart(context.getHorizonStart());
        draft.setHorizonEnd(context.getHorizonEnd());
        draft.setInputSnapshotJson(writeJson(context.getInputSnapshot()));
        draft.setResultJson(jsonResult);
        draft.setStatsJson(jsonStats);
        draft.setInputFingerprint(computed.getFingerprint());
        draft.setScheduledCount(computed.getResult().getScheduledCount());
        draft.setDelayedCount(computed.getResult().getDelayedCount());
        draft.setExcludedCount(computed.getResult().getExcludedCount());
        draft.setLockedCount(computed.getResult().getLockedCount());
        draft.setComputeMs(computed.getResult().getComputeMs());
        draft.setCreateTime(draft.getCreateTime() == null ? LocalDateTime.now() : draft.getCreateTime());
        return planRepository.save(draft);
    }

    /** 把当前草案留存为不可变快照；或对指定方案另存 */
    @Transactional
    public SchedulePlan saveSnapshot(Long sourcePlanId, String name, String operator) {
        SchedulePlan source = sourcePlanId == null
                ? planRepository.findDrafts().stream().findFirst().orElse(null)
                : planRepository.findById(sourcePlanId)
                        .orElseThrow(() -> new BizException("来源方案不存在：id=" + sourcePlanId));
        if (source == null) {
            throw new BizException("还没有可留存的排程结果，请先执行重算");
        }
        SchedulePlan snapshot = new SchedulePlan();
        snapshot.setPlanCode(generatePlanCode("SP"));
        snapshot.setName(name == null || name.isBlank() ? "留存方案 " + LocalDateTime.now() : name);
        snapshot.setPlanKind("SNAPSHOT");
        snapshot.setStatus("ACTIVE");
        snapshot.setRuleVersionId(source.getRuleVersionId());
        snapshot.setRuleVersionCode(source.getRuleVersionCode());
        snapshot.setHorizonStart(source.getHorizonStart());
        snapshot.setHorizonEnd(source.getHorizonEnd());
        snapshot.setInputSnapshotJson(source.getInputSnapshotJson());
        snapshot.setResultJson(source.getResultJson());
        snapshot.setStatsJson(source.getStatsJson());
        snapshot.setInputFingerprint(source.getInputFingerprint());
        snapshot.setScheduledCount(source.getScheduledCount());
        snapshot.setDelayedCount(source.getDelayedCount());
        snapshot.setExcludedCount(source.getExcludedCount());
        snapshot.setLockedCount(source.getLockedCount());
        snapshot.setComputeMs(source.getComputeMs());
        snapshot.setCreatedBy(operator);
        snapshot.setCreateTime(LocalDateTime.now());
        return planRepository.save(snapshot);
    }

    @Transactional(readOnly = true)
    public List<SchedulePlan> listPlans() {
        return planRepository.findAllActive();
    }

    /** 当前工作草案（含完整结果 JSON，供页面时间轴渲染）；无则 null */
    @Transactional(readOnly = true)
    public SchedulePlan draftPlan() {
        return planRepository.findDrafts().stream().findFirst().orElse(null);
    }

    @Transactional(readOnly = true)
    public SchedulePlan planDetail(Long id) {
        return planRepository.findById(id)
                .orElseThrow(() -> new BizException("排程方案不存在：id=" + id));
    }

    /** 选定一个方案为车间参考（全局至多一个 REFERENCE；草案不能被选为参考，必须先留存） */
    @Transactional
    public SchedulePlan markReference(Long id) {
        SchedulePlan target = planDetail(id);
        if ("DRAFT".equals(target.getPlanKind())) {
            throw new BizException("工作草案随输入变化会被覆盖，不能作为车间参考，请先「留存方案」再选定");
        }
        planRepository.findReference().ifPresent(ref -> {
            if (!ref.getId().equals(target.getId())) {
                ref.setStatus("ACTIVE");
                planRepository.save(ref);
            }
        });
        target.setStatus("REFERENCE");
        return planRepository.save(target);
    }

    @Transactional
    public void deleteSnapshot(Long id) {
        SchedulePlan plan = planDetail(id);
        if ("DRAFT".equals(plan.getPlanKind())) {
            throw new BizException("工作草案不能删除，它会随重算自动更新");
        }
        plan.setDeleted(1);
        planRepository.save(plan);
    }

    // ============================== 旧方案复算 / 差异 ==============================

    /**
     * 旧版本复算：用方案冻结的输入快照 + 当时的规则版本重新跑引擎。
     * 不写库、不改方案，仅返回结果与指标，并标注是否与留存结果一致（规则版本兼容验证）。
     */
    public Map<String, Object> replay(Long planId) {
        SchedulePlan plan = planDetail(planId);
        RuleSet ruleSet = ruleVersionService.loadRuleSet(plan.getRuleVersionId());
        ReplayInputs inputs = readSnapshot(plan.getInputSnapshotJson());

        SchedulingEngine engine = new SchedulingEngine(ruleSet, inputs.horizonStart, inputs.horizonEnd);
        engine.bindVats(inputs.vats);
        SchedulingEngine.ScheduleResult result = engine.solve(
                inputs.carts, inputs.maintenance, inputs.locks);

        Map<String, Object> replay = new LinkedHashMap<>();
        replay.put("planId", plan.getId());
        replay.put("planCode", plan.getPlanCode());
        replay.put("ruleVersionId", plan.getRuleVersionId());
        replay.put("ruleVersionCode", plan.getRuleVersionCode());
        replay.put("ruleVersionStatus", ruleVersionStatus(plan.getRuleVersionId()));
        replay.put("result", result);
        replay.put("vats", inputs.vats);
        replay.put("stats", buildStats(result));

        SchedulingEngine.ScheduleResult stored = readValue(plan.getResultJson(),
                SchedulingEngine.ScheduleResult.class);
        replay.put("consistent", Objects.equals(
                result.getScheduledCount(), stored.getScheduledCount())
                && Objects.equals(result.getDelayedCount(), stored.getDelayedCount())
                && Objects.equals(result.getExcludedCount(), stored.getExcludedCount())
                && Objects.equals(result.getTotalWashMinutes(), stored.getTotalWashMinutes())
                && Objects.equals(result.getTotalLateMinutes(), stored.getTotalLateMinutes()));
        replay.put("storedStats", readValue(plan.getStatsJson(), Map.class));
        return replay;
    }

    private String ruleVersionStatus(Long id) {
        return ruleRepository.findById(id).map(CleaningRuleVersion::getStatus).orElse("MISSING");
    }

    /** 两方案差异：按布车对比缸位/时段/结果类别 + 指标差异 */
    public Map<String, Object> diff(Long planAId, Long planBId) {
        SchedulePlan a = planDetail(planAId);
        SchedulePlan b = planDetail(planBId);
        SchedulingEngine.ScheduleResult ra = readValue(a.getResultJson(), SchedulingEngine.ScheduleResult.class);
        SchedulingEngine.ScheduleResult rb = readValue(b.getResultJson(), SchedulingEngine.ScheduleResult.class);

        Map<Long, SchedulingEngine.CartDecision> da = new LinkedHashMap<>();
        ra.getDecisions().forEach(d -> da.put(d.getCartId(), d));
        Map<Long, SchedulingEngine.CartDecision> db = new LinkedHashMap<>();
        rb.getDecisions().forEach(d -> db.put(d.getCartId(), d));
        Map<Long, SchedulingEngine.DelayedCart> delayA = index(ra.getDelayed());
        Map<Long, SchedulingEngine.DelayedCart> delayB = index(rb.getDelayed());
        Map<Long, SchedulingEngine.Exclusion> exA = indexEx(ra.getExclusions());
        Map<Long, SchedulingEngine.Exclusion> exB = indexEx(rb.getExclusions());

        List<Map<String, Object>> rows = new ArrayList<>();
        java.util.Set<Long> cartIds = new java.util.TreeSet<>();
        cartIds.addAll(da.keySet());
        cartIds.addAll(db.keySet());
        cartIds.addAll(delayA.keySet());
        cartIds.addAll(delayB.keySet());
        cartIds.addAll(exA.keySet());
        cartIds.addAll(exB.keySet());
        for (Long cartId : cartIds) {
            String outcomeA = outcomeOf(da.get(cartId), delayA.get(cartId), exA.get(cartId));
            String outcomeB = outcomeOf(db.get(cartId), delayB.get(cartId), exB.get(cartId));
            SchedulingEngine.CartDecision ca = da.get(cartId);
            SchedulingEngine.CartDecision cb = db.get(cartId);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("cartId", cartId);
            row.put("cartCode", firstText(ca, cb, delayA.get(cartId), delayB.get(cartId),
                    exA.get(cartId), exB.get(cartId)));
            row.put("outcomeA", outcomeA);
            row.put("outcomeB", outcomeB);
            row.put("vatA", ca == null ? null : ca.getVatCode());
            row.put("vatB", cb == null ? null : cb.getVatCode());
            row.put("startA", ca == null ? null : ca.getStartTime());
            row.put("startB", cb == null ? null : cb.getStartTime());
            row.put("lateA", ca == null ? 0 : ca.getLateMinutes());
            row.put("lateB", cb == null ? 0 : cb.getLateMinutes());
            boolean changed = !Objects.equals(outcomeA, outcomeB)
                    || !Objects.equals(row.get("vatA"), row.get("vatB"))
                    || !Objects.equals(row.get("startA"), row.get("startB"))
                    || !Objects.equals(row.get("lateA"), row.get("lateB"));
            row.put("changed", changed);
            rows.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planA", planSummary(a));
        result.put("planB", planSummary(b));
        result.put("metricDeltas", metricDeltas(a, b));
        result.put("changedCount", rows.stream().filter(r -> Boolean.TRUE.equals(r.get("changed"))).count());
        result.put("rows", rows);
        return result;
    }

    private Map<String, Object> metricDeltas(SchedulePlan a, SchedulePlan b) {
        Map<String, Object> ma = readValue(a.getStatsJson(), Map.class);
        Map<String, Object> mb = readValue(b.getStatsJson(), Map.class);
        Map<String, Object> deltas = new LinkedHashMap<>();
        for (String key : List.of("scheduledCount", "delayedCount", "excludedCount", "lateCount",
                "totalLateMinutes", "totalWashMinutes", "totalWashCost", "totalIdleMinutes")) {
            double va = asDouble(ma.get(key));
            double vb = asDouble(mb.get(key));
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("a", ma.get(key));
            d.put("b", mb.get(key));
            d.put("delta", vb - va);
            deltas.put(key, d);
        }
        return deltas;
    }

    private double asDouble(Object o) {
        return o == null ? 0 : Double.parseDouble(String.valueOf(o));
    }

    private Map<String, Object> planSummary(SchedulePlan p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("planCode", p.getPlanCode());
        m.put("name", p.getName());
        m.put("planKind", p.getPlanKind());
        m.put("status", p.getStatus());
        m.put("ruleVersionCode", p.getRuleVersionCode());
        m.put("createTime", p.getCreateTime());
        return m;
    }

    private String outcomeOf(SchedulingEngine.CartDecision d, SchedulingEngine.DelayedCart delayed,
                             SchedulingEngine.Exclusion ex) {
        if (d != null) {
            return d.getOutcome();
        }
        return delayed != null ? "DELAYED" : ex != null ? "EXCLUDED" : "NOT_PRESENT";
    }

    private String firstText(SchedulingEngine.CartDecision ca, SchedulingEngine.CartDecision cb,
                             SchedulingEngine.DelayedCart da, SchedulingEngine.DelayedCart db,
                             SchedulingEngine.Exclusion ea, SchedulingEngine.Exclusion eb) {
        if (ca != null) {
            return ca.getCartCode();
        }
        if (cb != null) {
            return cb.getCartCode();
        }
        if (da != null) {
            return da.getCartCode();
        }
        if (db != null) {
            return db.getCartCode();
        }
        if (ea != null) {
            return ea.getCartCode();
        }
        return eb == null ? null : eb.getCartCode();
    }

    private Map<Long, SchedulingEngine.DelayedCart> index(List<SchedulingEngine.DelayedCart> list) {
        Map<Long, SchedulingEngine.DelayedCart> map = new LinkedHashMap<>();
        list.forEach(d -> map.put(d.getCartId(), d));
        return map;
    }

    private Map<Long, SchedulingEngine.Exclusion> indexEx(List<SchedulingEngine.Exclusion> list) {
        Map<Long, SchedulingEngine.Exclusion> map = new LinkedHashMap<>();
        list.forEach(d -> map.put(d.getCartId(), d));
        return map;
    }

    // ============================== 布车排程输入 ==============================

    /** 只改交期/急单两个排程输入，绝不改布车状态或缸位 */
    @Transactional
    public ClothCart updateCartScheduleInput(Long cartId, LocalDateTime dueTime, Integer urgent) {
        ClothCart cart = cartRepository.findById(cartId)
                .orElseThrow(() -> new BizException("布车不存在或已报废：id=" + cartId));
        if (dueTime != null) {
            cart.setDueTime(dueTime);
        }
        if (urgent != null) {
            cart.setUrgent(urgent == 1 ? 1 : 0);
        }
        return cartRepository.save(cart);
    }

    // ============================== 快照反序列化（旧方案复算） ==============================

    private ReplayInputs readSnapshot(String json) {
        try {
            JsonMap root = objectMapper.readValue(json, JsonMap.class);
            ReplayInputs inputs = new ReplayInputs();
            inputs.horizonStart = LocalDateTime.parse(String.valueOf(root.get("horizonStart")));
            inputs.horizonEnd = LocalDateTime.parse(String.valueOf(root.get("horizonEnd")));

            inputs.vats = new ArrayList<>();
            for (JsonMap v : root.getList("vats", JsonMap.class)) {
                VatInput vi = new VatInput();
                vi.setId(v.getLong("id"));
                vi.setVatCode(v.getString("vatCode"));
                vi.setMachineType(v.getString("machineType"));
                vi.setCapacityKg(v.getDecimal("capacityKg"));
                inputs.vats.add(vi);
            }
            inputs.carts = new ArrayList<>();
            for (JsonMap c : root.getList("carts", JsonMap.class)) {
                CartInput ci = new CartInput();
                ci.setId(c.getLong("id"));
                ci.setCartCode(c.getString("cartCode"));
                ci.setFabricType(c.getString("fabricType"));
                ci.setColorCode(c.getString("colorCode"));
                ci.setColorName(c.getString("colorName"));
                ci.setCustomer(c.getString("customer"));
                ci.setMeters(c.getDecimal("meters"));
                ci.setWeightKg(c.getDecimal("weightKg"));
                ci.setDueTime(c.getDateTime("dueTime"));
                ci.setUrgent(Boolean.TRUE.equals(c.get("urgent")));
                ci.setReworkTimes(c.getInt("reworkTimes", 0));
                ci.setRecipeOk(Boolean.TRUE.equals(c.get("recipeOk")));
                ci.setCurveOk(Boolean.TRUE.equals(c.get("curveOk")));
                ci.setProcessMinutes(c.getInt("processMinutes", 0));
                inputs.carts.add(ci);
            }
            inputs.maintenance = readBlocks(root, "maintenance", false);
            inputs.locks = readBlocks(root, "locks", true);
            return inputs;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("方案输入快照解析失败，无法复算：" + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<SchedulingEngine.BlockInput> readBlocks(JsonMap root, String key, boolean lock) {
        List<SchedulingEngine.BlockInput> blocks = new ArrayList<>();
        for (JsonMap m : root.getList(key, JsonMap.class)) {
            SchedulingEngine.BlockInput b = new SchedulingEngine.BlockInput();
            b.setVatId(m.getLong("vatId"));
            b.setCartId(lock ? m.getLong("cartId") : null);
            b.setStart(m.getDateTime("startTime"));
            b.setEnd(m.getDateTime("endTime"));
            b.setLabel(lock ? m.getString("cartCode") : m.getString("vatCode"));
            b.setNote(m.getString(m.has("reason") ? "reason" : "note"));
            if (lock) {
                b.setCartCode(m.getString("cartCode"));
                b.setColorCode(m.getString("colorCode"));
                b.setColorHex(m.getString("colorHex"));
                b.setUrgent(Boolean.TRUE.equals(m.get("urgent")));
            }
            blocks.add(b);
        }
        return blocks;
    }

    // ============================== 工具 ==============================

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException("排程结果序列化失败：" + e.getMessage());
        }
    }

    private <T> T readValue(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new BizException("方案结果解析失败：" + e.getMessage());
        }
    }

    private String fingerprint(Map<String, Object> snapshot) {
        try {
            String canonical = objectMapper.writeValueAsString(snapshot);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String generatePlanCode(String prefix) {
        LocalDateTime now = LocalDateTime.now();
        return prefix + "-" + now.getYear() + String.format("%02d", now.getMonthValue())
                + String.format("%02d", now.getDayOfMonth()) + "-"
                + String.format("%06d", Math.abs(System.nanoTime()) % 1_000_000);
    }

    // ============================== 内部承载类 ==============================

    @Data
    public static class ScheduleContext {
        private CleaningRuleVersion ruleVersion;
        private RuleSet ruleSet;
        private List<VatInput> vats;
        private List<CartInput> carts;
        private List<SchedulingEngine.BlockInput> maintenance;
        private List<SchedulingEngine.BlockInput> locks;
        private LocalDateTime horizonStart;
        private LocalDateTime horizonEnd;
        private Map<String, Object> inputSnapshot;
        private String fingerprint;
    }

    @Data
    public static class ComputedPlan {
        private ScheduleContext context;
        private SchedulingEngine.ScheduleResult result;
        private Map<String, Object> stats;
        private String fingerprint;
        private Map<String, Object> inputSnapshot;
    }

    private static class ReplayInputs {
        LocalDateTime horizonStart;
        LocalDateTime horizonEnd;
        List<VatInput> vats;
        List<CartInput> carts;
        List<SchedulingEngine.BlockInput> maintenance;
        List<SchedulingEngine.BlockInput> locks;
    }

    /** 弱类型 JSON Map，供快照反序列化使用 */
    public static class JsonMap extends LinkedHashMap<String, Object> {
        public String getString(String key) {
            Object v = get(key);
            return v == null ? null : String.valueOf(v);
        }

        public Long getLong(String key) {
            Object v = get(key);
            return v == null ? null : Long.parseLong(String.valueOf(v));
        }

        public Integer getInt(String key, int dft) {
            Object v = get(key);
            return v == null ? dft : Integer.parseInt(String.valueOf(v));
        }

        public BigDecimal getDecimal(String key) {
            Object v = get(key);
            return v == null ? null : new BigDecimal(String.valueOf(v));
        }

        public LocalDateTime getDateTime(String key) {
            Object v = get(key);
            if (v == null) {
                return null;
            }
            String s = String.valueOf(v);
            return LocalDateTime.parse(s.replace(" ", "T"));
        }

        public boolean has(String key) {
            return containsKey(key) && get(key) != null;
        }

        @SuppressWarnings("unchecked")
        public <T> List<T> getList(String key, Class<T> type) {
            Object v = get(key);
            if (!(v instanceof List<?> list)) {
                return new ArrayList<>();
            }
            List<T> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    JsonMap jm = new JsonMap();
                    map.forEach((k, val) -> jm.put(String.valueOf(k), val));
                    result.add((T) jm);
                } else {
                    result.add((T) item);
                }
            }
            return result;
        }
    }
}
