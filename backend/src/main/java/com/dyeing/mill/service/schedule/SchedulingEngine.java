package com.dyeing.mill.service.schedule;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import lombok.Data;

/**
 * 换色排程引擎（确定性、可解释）。
 *
 * 设计口径：
 * 1. 硬约束（任何方案都不得违反）：同缸作业不重叠、清洗时段必须留足、维护窗不可用、
 *    容量不超、机型适配、人工锁定段固定不动。只按颜色或交期排序会同时破坏这些约束，
 *    因此引擎采用「逐缸时间轴 + 可行位插入」而不是简单排序。
 * 2. 软目标（在所有可行位里选分最低的）：逾期分钟（急单权重远高于清洗成本）、
 *    空档分钟、清洗成本与时长。急单逾期 60 元/分钟，最贵的特殊染料清洗 1200 元
 *    仅相当于 20 分钟逾期——绝不可能为省一次清洗把急单压后数小时。
 * 3. 引擎只依赖 {@link RuleSet} 口径，旧方案按旧版本复算自然得到当时的清洗规则与解释。
 */
public class SchedulingEngine {

    /** 原因码：页面可据此做中文徽标/筛选 */
    public static final String R_SCHEDULED = "SCHEDULED";
    public static final String R_SCHEDULED_LATE = "SCHEDULED_LATE";
    public static final String R_LOCKED = "LOCKED";
    public static final String R_DELAYED = "DELAYED";
    public static final String R_EX_RECIPE = "EXCLUDED_RECIPE";
    public static final String R_EX_CURVE = "EXCLUDED_CURVE";
    public static final String R_EX_CAPACITY = "EXCLUDED_CAPACITY";
    public static final String R_EX_MACHINE = "EXCLUDED_MACHINE";

    private final RuleSet rule;
    private final LocalDateTime horizonStart;
    private final LocalDateTime horizonEnd;
    private final long slotMinutes;

    /** 色号 → 颜色族定义 */
    private final Map<String, RuleSet.ColorFamily> familyByColor = new LinkedHashMap<>();

    public SchedulingEngine(RuleSet rule, LocalDateTime horizonStart, LocalDateTime horizonEnd) {
        this.rule = rule;
        this.horizonStart = truncateToSlot(horizonStart, rule.getParams().getSlotMinutes());
        this.horizonEnd = truncateToSlot(horizonEnd, rule.getParams().getSlotMinutes());
        this.slotMinutes = rule.getParams().getSlotMinutes();
        if (rule.getMatrix() != null && rule.getMatrix().getColorFamilies() != null) {
            for (RuleSet.ColorFamily family : rule.getMatrix().getColorFamilies()) {
                if (family.getColorCodes() != null) {
                    for (String code : family.getColorCodes()) {
                        familyByColor.put(code, family);
                    }
                }
            }
        }
    }

    public LocalDateTime getHorizonStart() {
        return horizonStart;
    }

    public LocalDateTime getHorizonEnd() {
        return horizonEnd;
    }

    // ============================== 清洗矩阵口径 ==============================

    public RuleSet.ColorFamily familyOf(String colorCode) {
        return familyByColor.get(colorCode);
    }

    /** 清洗规则结果：分类 + 分钟 + 成本 + 中文标签 + 依据说明 */
    @Data
    public static class WashRule {
        private String category;
        private String categoryLabel;
        private int washMinutes;
        private int costYuan;
        private String basis;
    }

    /**
     * 两个颜色（同缸先后作业）之间的清洗规则。
     * 顺序敏感：fromColor 先染、toColor 后染；任一端涉及特殊染料族 → SPECIAL。
     * 逐族覆写行优先，否则按深浅档分类走默认值。
     */
    public WashRule washBetween(String fromColor, String toColor) {
        RuleSet.ColorFamily from = familyByColor.get(fromColor);
        RuleSet.ColorFamily to = familyByColor.get(toColor);
        WashRule result = new WashRule();
        if (from == null || to == null) {
            result.setCategory("SAME");
            applyDefault(result, "SAME");
            result.setBasis("至少一个色号未维护颜色族，按同色续染保守处理");
            return result;
        }
        if (Objects.equals(from.getFamilyCode(), to.getFamilyCode())) {
            result.setCategory("SAME");
            applyDefault(result, "SAME");
            result.setBasis("同色族「" + from.getFamilyName() + "」续染，仅需过清水");
            MatrixRowOverride override = findOverride(from.getFamilyCode(), to.getFamilyCode());
            if (override != null) {
                applyOverride(result, override);
            }
            return result;
        }
        if (from.isSpecial() || to.isSpecial()) {
            result.setCategory("SPECIAL");
            applyDefault(result, "SPECIAL");
            result.setBasis("涉及特殊染料族（"
                    + (from.isSpecial() ? familyLabel(from) + "→" : "")
                    + familyLabel(to) + "），需专项清洗，避免离子型染料交叉沉淀");
            MatrixRowOverride override = findOverride(from.getFamilyCode(), to.getFamilyCode());
            if (override != null) {
                applyOverride(result, override);
            }
            return result;
        }
        String category = classifyByShade(from.getShade(), to.getShade());
        result.setCategory(category);
        applyDefault(result, category);
        result.setBasis(familyLabel(from) + "（" + shadeName(from.getShade()) + "）→"
                + familyLabel(to) + "（" + shadeName(to.getShade()) + "），按「"
                + result.getCategoryLabel() + "」默认口径");
        MatrixRowOverride override = findOverride(from.getFamilyCode(), to.getFamilyCode());
        if (override != null) {
            applyOverride(result, override);
            result.setBasis("颜色族对「" + from.getFamilyName() + "→" + to.getFamilyName()
                    + "」在矩阵中有专用覆写（" + result.getCategoryLabel() + " "
                    + result.getWashMinutes() + " 分钟/" + result.getCostYuan() + " 元），优先于深浅默认值");
        }
        return result;
    }

    private record MatrixRowOverride(RuleSet.MatrixRow row) {}

    private MatrixRowOverride findOverride(String fromFamily, String toFamily) {
        if (rule.getMatrix() == null || rule.getMatrix().getRows() == null) {
            return null;
        }
        for (RuleSet.MatrixRow row : rule.getMatrix().getRows()) {
            if (Objects.equals(row.getFromFamily(), fromFamily)
                    && Objects.equals(row.getToFamily(), toFamily)) {
                return new MatrixRowOverride(row);
            }
        }
        return null;
    }

    private void applyOverride(WashRule result, MatrixRowOverride override) {
        RuleSet.MatrixRow row = override.row();
        if (row.getCategory() != null) {
            result.setCategory(row.getCategory());
            result.setCategoryLabel(categoryLabel(row.getCategory()));
        }
        if (row.getWashMinutes() != null) {
            result.setWashMinutes(row.getWashMinutes());
        }
        if (row.getCostYuan() != null) {
            result.setCostYuan(row.getCostYuan());
        }
    }

    private void applyDefault(WashRule result, String category) {
        result.setCategory(category);
        result.setCategoryLabel(categoryLabel(category));
        RuleSet.CategoryDefault def = defaultOf(category);
        if (def != null) {
            result.setWashMinutes(def.getWashMinutes());
            result.setCostYuan(def.getCostYuan());
            result.setCategoryLabel(def.getLabel());
        }
    }

    private RuleSet.CategoryDefault defaultOf(String category) {
        if (rule.getMatrix() == null || rule.getMatrix().getCategoryDefaults() == null) {
            return null;
        }
        return rule.getMatrix().getCategoryDefaults().stream()
                .filter(d -> Objects.equals(d.getCategory(), category))
                .findFirst().orElse(null);
    }

    /** 深浅档分类：等档/中转中按浅转深处理；显式深→浅才是 DEEP_TO_LIGHT */
    private String classifyByShade(String fromShade, String toShade) {
        int from = shadeRank(fromShade);
        int to = shadeRank(toShade);
        if (from > to) {
            return "DEEP_TO_LIGHT";
        }
        return "LIGHT_TO_DEEP";
    }

    private int shadeRank(String shade) {
        return switch (shade == null ? "" : shade) {
            case "DEEP" -> 2;
            case "MID" -> 1;
            default -> 0;
        };
    }

    private String shadeName(String shade) {
        return switch (shade == null ? "" : shade) {
            case "DEEP" -> "深色";
            case "MID" -> "中档色";
            case "LIGHT" -> "浅色";
            default -> shade;
        };
    }

    private String familyLabel(RuleSet.ColorFamily family) {
        return family.getFamilyName() != null ? family.getFamilyName() : family.getFamilyCode();
    }

    private String categoryLabel(String category) {
        return switch (category == null ? "" : category) {
            case "SAME" -> "同色续染";
            case "LIGHT_TO_DEEP" -> "浅转深";
            case "DEEP_TO_LIGHT" -> "深转浅";
            case "SPECIAL" -> "特殊染料切换";
            default -> category;
        };
    }

    // ============================== 排程计算 ==============================

    /**
     * 排程主入口。
     *
     * @param carts          全部待排候选布车（含资料完备性预解析）
     * @param maintenanceAll 全部维护时段（只保留与视野相交的部分）
     * @param locksAll       全部人工锁定段
     * @return 完整排程结果
     */
    public ScheduleResult solve(List<CartInput> carts,
                                List<BlockInput> maintenanceAll,
                                List<BlockInput> locksAll) {
        long t0 = System.currentTimeMillis();

        // 1) 每缸固定块时间轴：视野内的维护 + 锁定
        Map<Long, VatTimeline> timelines = new LinkedHashMap<>();
        for (BlockInput m : maintenanceAll) {
            LocalDateTime s = max(m.getStart(), horizonStart);
            LocalDateTime e = min(m.getEnd(), horizonEnd);
            if (!e.isAfter(s)) {
                continue;
            }
            timeline(timelines, m.getVatId()).fixed.add(new FixedBlock(s, e, "MAINTENANCE",
                    m.getCartId(), m.getLabel(), m.getNote()));
        }
        List<CartInput> lockedCarts = new ArrayList<>();
        Map<Long, BlockInput> lockInputByCart = new LinkedHashMap<>();
        for (BlockInput lock : locksAll) {
            LocalDateTime s = max(lock.getStart(), horizonStart);
            LocalDateTime e = min(lock.getEnd(), horizonEnd);
            if (!e.isAfter(s)) {
                continue;
            }
            FixedBlock lockBlock = new FixedBlock(s, e, "LOCK",
                    lock.getCartId(), lock.getLabel(), lock.getNote());
            lockBlock.colorCode = lock.getColorCode();
            lockBlock.colorHex = lock.getColorHex();
            lockBlock.cartCode = lock.getCartCode();
            lockBlock.urgent = lock.isUrgent();
            lockBlock.locked = true;
            carts.stream().filter(c -> Objects.equals(c.getId(), lock.getCartId())).findFirst()
                    .ifPresent(c -> {
                        lockedCarts.add(c);
                        lockBlock.colorName = c.getColorName();
                        lockInputByCart.put(c.getId(), lock);
                    });
            timeline(timelines, lock.getVatId()).fixed.add(lockBlock);
        }
        timelines.values().forEach(t -> t.fixed.sort(Comparator.comparing(b -> b.start)));

        ScheduleResult result = new ScheduleResult();
        result.setHorizonStart(horizonStart);
        result.setHorizonEnd(horizonEnd);
        result.setSlotMinutes((int) slotMinutes);
        result.setRuleVersionId(rule.getVersionId());
        result.setRuleVersionCode(rule.getVersionCode());

        // 2) 硬资格预筛：资料/容量/机型（与缸位无关的排除单列；维护冲突不算排除，算延后）
        List<CartInput> eligible = new ArrayList<>();
        for (CartInput cart : carts) {
            if (lockedCarts.contains(cart)) {
                continue; // 锁定车单独解释
            }
            List<String> reasons = new ArrayList<>();
            if (!cart.isRecipeOk()) {
                reasons.add(cart.getRecipeIssue() != null ? cart.getRecipeIssue()
                        : "布车 " + cart.getCartCode() + " 色号 " + cart.getColorCode()
                        + "、布种 " + cart.getFabricType() + " 缺少已发布配方");
            }
            if (!cart.isCurveOk()) {
                reasons.add(cart.getCurveIssue() != null ? cart.getCurveIssue()
                        : "布车 " + cart.getCartCode() + " 缺少与配方匹配的工艺曲线");
            }
            if (!machineEligibility(cart).eligible && cart.isRecipeOk()) {
                reasons.add("布种 " + cart.getFabricType() + " 在当前规则版本中没有任何适配机型（"
                        + machineEligibility(cart).detail + "），全部染缸机型均不适配");
            }
            List<String> capacityReasons = new ArrayList<>();
            boolean anyVatFits = false;
            for (VatInput vat : vatOrder()) {
                if (!machineMatches(cart, vat)) {
                    continue;
                }
                if (vat.getCapacityKg() != null && cart.getWeightKg() != null
                        && cart.getWeightKg().compareTo(vat.getCapacityKg()) > 0) {
                    capacityReasons.add(vat.getVatCode() + " 仅 " + vat.getCapacityKg()
                            + "kg，超出 " + cart.getWeightKg().subtract(vat.getCapacityKg())
                            .setScale(1, java.math.RoundingMode.HALF_UP) + "kg");
                } else {
                    anyVatFits = true;
                }
            }
            if (!reasons.isEmpty() || (!capacityReasons.isEmpty() && !anyVatFits)) {
                if (!capacityReasons.isEmpty() && !anyVatFits) {
                    reasons.add("布车折算布重 " + cart.getWeightKg() + "kg，所有适配机型染缸容量均不足："
                            + String.join("；", capacityReasons));
                }
                result.getExclusions().add(buildExclusion(cart, reasons));
            } else {
                eligible.add(cart);
            }
        }

        // 3) 急单 → 交期 → 长作业优先；同色族聚拢（在不牺牲交期的前提下减少清洗）
        eligible.sort(Comparator
                .comparing((CartInput c) -> c.isUrgent() ? 0 : 1)
                .thenComparing(c -> c.getDueTime() == null ? LocalDateTime.MAX : c.getDueTime())
                .thenComparing(c -> familyCode(c.getColorCode()))
                .thenComparing(Comparator.comparing(CartInput::getProcessMinutes).reversed())
                .thenComparing(CartInput::getCartCode));

        // 4) 逐车在全部适配缸的全部可行位中选代价最低的位置（不是简单排到某一缸队尾）
        for (CartInput cart : eligible) {
            Placement best = null;
            for (VatInput vat : vatOrder()) {
                if (!machineMatches(cart, vat)) {
                    continue;
                }
                if (vat.getCapacityKg() != null && cart.getWeightKg() != null
                        && cart.getWeightKg().compareTo(vat.getCapacityKg()) > 0) {
                    continue;
                }
                VatTimeline tl = timelines.computeIfAbsent(vat.getId(), k -> new VatTimeline());
                Placement p = findBestPlacement(tl, vat, cart);
                if (p != null && (best == null || p.score < best.score)) {
                    best = p;
                }
            }
            if (best == null) {
                result.getDelayed().add(buildDelayed(cart, timelines));
            } else {
                best.tl.placed.add(best);
            }
        }

        // 5) 一次不恶化交期的同色族聚拢重排（减少空档与清洗；急单交期受保护）
        coalesceRuns(timelines);

        // 6) 输出时间轴（染色块 + 清洗段 + 维护 + 锁定）与每车解释
        assembleResult(result, timelines, lockedCarts, carts, lockInputByCart);
        result.setComputeMs(System.currentTimeMillis() - t0);
        return result;
    }

    private final List<VatInput> vats = new ArrayList<>();

    private List<VatInput> vatOrder() {
        return vats;
    }

    /** 服务层注入染缸清单（保持台账顺序） */
    public void bindVats(List<VatInput> vatInputs) {
        vats.clear();
        vats.addAll(vatInputs);
    }

    private boolean machineMatches(CartInput cart, VatInput vat) {
        MachineCheck check = machineEligibility(cart);
        return check.eligible && check.machineTypes.contains(vat.getMachineType());
    }

    @Data
    private static class MachineCheck {
        private boolean eligible;
        private List<String> machineTypes = new ArrayList<>();
        private String detail;
    }

    private MachineCheck machineEligibility(CartInput cart) {
        MachineCheck check = new MachineCheck();
        RuleSet.FabricMachine fm = rule.getFabricMachines() == null ? null
                : rule.getFabricMachines().stream()
                .filter(f -> Objects.equals(f.getFabricType(), cart.getFabricType()))
                .findFirst().orElse(null);
        if (fm == null || fm.getMachineTypes() == null || fm.getMachineTypes().isEmpty()) {
            check.setEligible(false);
            check.setDetail("规则版本 " + rule.getVersionCode() + " 未维护布种 " + cart.getFabricType());
            return check;
        }
        check.setMachineTypes(fm.getMachineTypes());
        check.setEligible(vats.stream().anyMatch(v -> fm.getMachineTypes().contains(v.getMachineType())));
        if (!check.isEligible()) {
            check.setDetail("需要 " + String.join("/", fm.getMachineTypes())
                    + "，当前车间无此机型");
        }
        return check;
    }

    /** 某缸某车的最优可行位 */
    private Placement findBestPlacement(VatTimeline tl, VatInput vat, CartInput cart) {
        int duration = alignedMinutes(cart.getProcessMinutes());
        List<Interval> blocked = tl.blockedIntervals();
        List<LocalDateTime> candidateStarts = candidateStarts(tl, blocked, cart, duration);
        Placement best = null;
        for (LocalDateTime start : candidateStarts) {
            LocalDateTime end = start.plusMinutes(duration);
            if (end.isAfter(horizonEnd)) {
                continue;
            }
            Placement p = scorePlacement(tl, vat, cart, start, end);
            if (p != null && (best == null || p.score < best.score)) {
                best = p;
            }
        }
        return best;
    }

    /**
     * 候选起点：视野起点；以及每个固定/已排块之后、满足「前序颜色→本车」清洗时长的最早点。
     * 这样紧接同色（清洗 10 分钟）能早排，深转浅（清洗 90 分钟）自动后移，
     * 不会出现把作业排在清洗窗口里的非法方案。
     */
    private List<LocalDateTime> candidateStarts(VatTimeline tl, List<Interval> blocked,
                                                CartInput cart, int duration) {
        List<LocalDateTime> starts = new ArrayList<>();
        starts.add(ceilSlot(horizonStart));
        List<FixedBlock> seq = tl.sequence();
        for (FixedBlock prev : seq) {
            // 完全落在视野结束之后的块，其后沿没有参考意义
            if (!prev.end.isBefore(horizonEnd)) {
                continue;
            }
            LocalDateTime earliest = ceilSlot(prev.end);
            if (prev.colorCode != null) {
                WashRule wash = washBetween(prev.colorCode, cart.getColorCode());
                earliest = ceilSlot(prev.end.plusMinutes(wash.getWashMinutes()));
            }
            starts.add(earliest);
        }
        starts.removeIf(s -> !s.isBefore(horizonEnd) || s.isBefore(horizonStart));
        starts.sort(LocalDateTime::compareTo);
        List<LocalDateTime> dedup = new ArrayList<>();
        for (LocalDateTime s : starts) {
            if (dedup.isEmpty() || !dedup.get(dedup.size() - 1).equals(s)) {
                dedup.add(s);
            }
        }
        return dedup;
    }

    private Placement scorePlacement(VatTimeline tl, VatInput vat, CartInput cart,
                                     LocalDateTime start, LocalDateTime end) {
        List<Interval> blocked = tl.blockedIntervals();
        for (Interval b : blocked) {
            if (start.isBefore(b.end) && b.start.isBefore(end)) {
                return null; // 与固定块/已排作业重叠（候选生成外的防御性判断）
            }
        }
        FixedBlock prev = lastBlockEndingBefore(tl, start);
        FixedBlock next = firstBlockStartingAfter(tl, end);

        Placement p = new Placement();
        p.tl = tl;
        p.vat = vat;
        p.cart = cart;
        p.start = start;
        p.end = end;

        // 前序颜色 → 本次的清洗；前序为维护块则视为缸体已复位，空档只从维护结束起算
        LocalDateTime idleOrigin = horizonStart;
        if (prev != null) {
            idleOrigin = prev.end;
        }
        if (prev != null && prev.colorCode != null) {
            p.washBefore = washBetween(prev.colorCode, cart.getColorCode());
            LocalDateTime needStart = start.minusMinutes(p.washBefore.getWashMinutes());
            if (prev.end.isAfter(needStart)) {
                return null; // 留不出必需清洗（紧贴位一定留得出，插入位可能留不出）
            }
            // 清洗段本身也不得与任何块重叠
            for (Interval b : blocked) {
                if (needStart.isBefore(b.end) && b.start.isBefore(start)) {
                    return null;
                }
            }
            p.idleBefore = minutesBetween(prev.end, needStart);
            p.washStart = needStart;
        } else {
            p.idleBefore = minutesBetween(idleOrigin, start);
        }
        // 本次 → 后序颜色的清洗（落位后要给后序留口径，计入本次决策的未来代价估计）
        if (next != null && next.colorCode != null) {
            WashRule after = washBetween(cart.getColorCode(), next.colorCode);
            LocalDateTime needEnd = end.plusMinutes(after.getWashMinutes());
            if (needEnd.isAfter(next.start)) {
                return null;
            }
            p.washAfter = after;
        }

        // 软目标评分（元当量）：逾期（急单高权重）+ 空档 + 本次清洗成本
        RuleSet.Params params = rule.getParams();
        long late = 0;
        if (cart.getDueTime() != null && end.isAfter(cart.getDueTime())) {
            late = minutesBetween(cart.getDueTime(), end);
        }
        double latenessCost = late * (cart.isUrgent()
                ? params.getUrgentLatenessWeight() : params.getLatenessWeight());
        double idleCost = p.idleBefore * params.getIdleWeight();
        double washCost = (p.washBefore == null ? 0 : p.washBefore.getCostYuan())
                * params.getWashCostWeight();
        // 该缸此前是否已有染色作业（已排车或锁定块；仅维护不算），没有则记一次新开缸准备成本
        boolean hasPriorJob = tl.placed.stream().anyMatch(o -> !o.end.isAfter(start))
                || tl.fixed.stream().anyMatch(f -> "LOCK".equals(f.kind) && !f.end.isAfter(start));
        double newVatPenalty = hasPriorJob ? 0.0 : params.getNewVatPenaltyYuan();
        p.openingNewVat = !hasPriorJob;
        p.score = latenessCost + idleCost + washCost + newVatPenalty;
        p.lateMinutes = late;
        p.expectedCost = latenessCost + idleCost
                + (p.washBefore == null ? 0 : p.washBefore.getCostYuan()) + newVatPenalty;
        return p;
    }

    private FixedBlock lastBlockEndingBefore(VatTimeline tl, LocalDateTime start) {
        FixedBlock found = null;
        for (FixedBlock b : tl.sequence()) {
            if (!b.end.isAfter(start) && (found == null || b.end.isAfter(found.end))) {
                found = b;
            }
        }
        return found;
    }

    private FixedBlock firstBlockStartingAfter(VatTimeline tl, LocalDateTime end) {
        for (FixedBlock b : tl.sequence()) {
            if (!b.start.isBefore(end)) {
                return b;
            }
        }
        return null;
    }

    /**
     * 不恶化交期的同色族聚拢重排（两轮）：
     * 对每个已排车，临时把它从缸轴拿掉，在「同色族后序作业之后」重新找全缸最优可行位。
     * 只要新位评分更优且不增加该车逾期（急单一毫分钟逾期都不允许增加），就接受移动。
     * 这样可以消除首车分缸造成的额外清洗/空档，又不会为省清洗牺牲交期。
     */
    private void coalesceRuns(Map<Long, VatTimeline> timelines) {
        for (int pass = 0; pass < 2; pass++) {
            boolean moved = false;
            List<VatInput> snapshotVats = new ArrayList<>(vats);
            for (VatInput vat : snapshotVats) {
                VatTimeline tl = timelines.get(vat.getId());
                if (tl == null || tl.placed.size() < 2) {
                    continue;
                }
                List<Placement> snapshot = new ArrayList<>(tl.placed);
                for (Placement cur : snapshot) {
                    if (!tl.placed.contains(cur)) {
                        continue;
                    }
                    Placement better = findBetterSameFamilySpot(timelines, tl, cur);
                    if (better != null) {
                        VatTimeline oldTl = cur.tl;
                        oldTl.placed.remove(cur);
                        better.tl.placed.add(better);
                        moved = true;
                    }
                }
            }
            if (!moved) {
                break;
            }
        }
    }

    /** 找一个同色族锚点后的更优可行位；找不到/更差/增加逾期则返回 null */
    private Placement findBetterSameFamilySpot(Map<Long, VatTimeline> timelines,
                                               VatTimeline tl, Placement cur) {
        boolean sameFamilyExists = tl.placed.stream()
                .anyMatch(o -> o != cur && Objects.equals(
                        familyCode(o.cart.getColorCode()), familyCode(cur.cart.getColorCode())));
        if (!sameFamilyExists) {
            return null;
        }
        long oldLate = cur.lateMinutes;
        double oldScore = cur.score;

        // 先把当前车从所有缸轴摘除，试探结束时统一恢复，杜绝中途多次加回
        cur.tl.placed.remove(cur);
        Placement best = null;
        try {
            for (VatInput vat : vats) {
                if (!machineMatches(cur.cart, vat)) {
                    continue;
                }
                if (vat.getCapacityKg() != null && cur.cart.getWeightKg() != null
                        && cur.cart.getWeightKg().compareTo(vat.getCapacityKg()) > 0) {
                    continue;
                }
                VatTimeline candidateTl = timelines.computeIfAbsent(vat.getId(), k -> new VatTimeline());
                Placement p = findBestPlacement(candidateTl, vat, cur.cart);
                if (p == null) {
                    continue;
                }
                boolean hasSameFamilyBefore = candidateTl.sequence().stream()
                        .anyMatch(b -> "PLACED".equals(b.kind) && !b.end.isAfter(p.start)
                                && Objects.equals(familyCode(b.colorCode), familyCode(cur.cart.getColorCode())));
                boolean lateOk = cur.cart.isUrgent() ? p.lateMinutes <= oldLate
                        : p.lateMinutes <= oldLate + slotMinutes;
                if (lateOk && p.score < oldScore - 0.001 && hasSameFamilyBefore
                        && (best == null || p.score < best.score)) {
                    best = p;
                }
            }
        } finally {
            if (best == null) {
                tl.placed.add(cur);
            }
        }
        return best;
    }

    // ============================== 结果组装与解释 ==============================

    private void assembleResult(ScheduleResult result, Map<Long, VatTimeline> timelines,
                                List<CartInput> lockedCarts, List<CartInput> allCarts,
                                Map<Long, BlockInput> lockInputByCart) {
        for (VatInput vat : vatOrder()) {
            VatTimeline tl = timelines.computeIfAbsent(vat.getId(), k -> new VatTimeline());
            List<FixedBlock> seq = tl.sequence();
            List<TimelineItem> items = new ArrayList<>();
            long washMinutesTotal = 0;
            long washCostTotal = 0;
            long idleTotal = 0;
            long busyTotal = 0;

            for (int i = 0; i < seq.size(); i++) {
                FixedBlock cur = seq.get(i);
                if (i > 0) {
                    FixedBlock prev = seq.get(i - 1);
                    LocalDateTime gapStart = prev.end;
                    LocalDateTime gapEnd = cur.start;
                    if (gapEnd.isAfter(gapStart)) {
                        if (cur.colorCode != null && prev.colorCode != null) {
                            WashRule wash = washBetween(prev.colorCode, cur.colorCode);
                            LocalDateTime washStart = gapEnd.minusMinutes(wash.getWashMinutes());
                            if (washStart.isBefore(gapStart)) {
                                washStart = gapStart;
                            }
                            if (washStart.isAfter(gapStart)) {
                                idleTotal += minutesBetween(gapStart, washStart);
                                items.add(TimelineItem.idle(vat, gapStart, washStart));
                            }
                            items.add(TimelineItem.wash(vat, washStart, gapEnd,
                                    prev.colorCode, cur.colorCode, wash));
                            washMinutesTotal += minutesBetween(washStart, gapEnd);
                            washCostTotal += wash.getCostYuan();
                        } else {
                            idleTotal += minutesBetween(gapStart, gapEnd);
                            items.add(TimelineItem.idle(vat, gapStart, gapEnd));
                        }
                    }
                } else if (cur.start.isAfter(horizonStart)) {
                    idleTotal += minutesBetween(horizonStart, cur.start);
                    items.add(TimelineItem.idle(vat, horizonStart, cur.start));
                }

                if ("MAINTENANCE".equals(cur.kind)) {
                    items.add(TimelineItem.maintenance(vat, cur));
                } else if ("LOCK".equals(cur.kind)) {
                    items.add(TimelineItem.lock(vat, cur));
                } else {
                    items.add(TimelineItem.process(vat, cur));
                    busyTotal += minutesBetween(cur.start, cur.end);
                }
            }
            LocalDateTime lastEnd = seq.isEmpty() ? horizonStart : seq.get(seq.size() - 1).end;
            if (lastEnd.isBefore(horizonEnd)) {
                idleTotal += minutesBetween(lastEnd, horizonEnd);
            }

            long horizonMinutes = minutesBetween(horizonStart, horizonEnd);
            VatLoad load = new VatLoad();
            load.setVatId(vat.getId());
            load.setVatCode(vat.getVatCode());
            load.setMachineType(vat.getMachineType());
            load.setCapacityKg(vat.getCapacityKg());
            load.setBusyMinutes(busyTotal);
            load.setWashMinutes(washMinutesTotal);
            load.setMaintenanceMinutes(maintenanceMinutesOf(tl));
            load.setIdleMinutes(Math.max(0, idleTotal));
            load.setLoadRate(horizonMinutes == 0 ? 0.0
                    : round2((busyTotal + washMinutesTotal) * 100.0 / horizonMinutes));
            result.getVatLoads().add(load);
            result.getTimeline().addAll(items);
            result.setTotalWashMinutes(result.getTotalWashMinutes() + washMinutesTotal);
            result.setTotalWashCost(result.getTotalWashCost() + washCostTotal);
            result.setTotalIdleMinutes(result.getTotalIdleMinutes() + idleTotal);
            result.setTotalBusyMinutes(result.getTotalBusyMinutes() + busyTotal);
        }

        // 已排车解释
        for (VatTimeline tl : timelines.values()) {
            for (Placement p : tl.placed) {
                result.getDecisions().add(decisionOfPlaced(p, tl));
            }
        }
        // 锁定车解释
        for (CartInput cart : lockedCarts) {
            result.getDecisions().add(decisionOfLocked(cart, lockInputByCart.get(cart.getId())));
        }
        result.setScheduledCount((int) result.getDecisions().stream()
                .filter(d -> "SCHEDULED".equals(d.getOutcome()) || "SCHEDULED_LATE".equals(d.getOutcome()))
                .count());
        result.setLockedCount(lockedCarts.size());
        result.setDelayedCount(result.getDelayed().size());
        result.setExcludedCount(result.getExclusions().size());
        result.setLateCount((int) result.getDecisions().stream()
                .filter(d -> "SCHEDULED_LATE".equals(d.getOutcome())).count());
        result.setTotalLateMinutes(result.getDecisions().stream()
                .mapToLong(CartDecision::getLateMinutes).sum());
        result.setTotalCarts(allCarts.size());
    }

    private long maintenanceMinutesOf(VatTimeline tl) {
        return tl.fixed.stream().filter(f -> "MAINTENANCE".equals(f.kind))
                .mapToLong(f -> minutesBetween(max(f.start, horizonStart), min(f.end, horizonEnd)))
                .sum();
    }

    private CartDecision decisionOfPlaced(Placement p, VatTimeline tl) {
        CartDecision d = new CartDecision();
        CartInput cart = p.cart;
        d.setCartId(cart.getId());
        d.setCartCode(cart.getCartCode());
        d.setColorCode(cart.getColorCode());
        d.setColorName(cart.getColorName());
        d.setColorHex(cart.getColorHex());
        d.setFabricType(cart.getFabricType());
        d.setUrgent(cart.isUrgent());
        d.setDueTime(cart.getDueTime());
        d.setWeightKg(cart.getWeightKg());
        d.setVatId(p.vat.getId());
        d.setVatCode(p.vat.getVatCode());
        d.setMachineType(p.vat.getMachineType());
        d.setStartTime(p.start);
        d.setEndTime(p.end);
        d.setLateMinutes(p.lateMinutes);
        d.setLocked(false);

        List<String> why = new ArrayList<>();
        why.add("排入 " + p.vat.getVatCode() + "（" + machineName(p.vat.getMachineType())
                + "，容量 " + p.vat.getCapacityKg() + "kg，本车折算 " + cart.getWeightKg()
                + "kg），" + p.start.toLocalDate() + " " + p.start.toLocalTime()
                + " 开缸、" + p.end.toLocalTime() + " 出缸，作业 "
                + alignedMinutes(cart.getProcessMinutes()) + " 分钟");
        if (p.washBefore != null) {
            why.add("前序为 " + prevColorName(tl, p) + "，按规则版本 " + rule.getVersionCode()
                    + " 需「" + p.washBefore.getCategoryLabel() + "」清洗 "
                    + p.washBefore.getWashMinutes() + " 分钟（约 " + p.washBefore.getCostYuan()
                    + " 元），已计入 " + p.washStart.toLocalTime() + " 起的清洗段；"
                    + p.washBefore.getBasis());
        } else if (prevBlockIsMaintenance(tl, p)) {
            why.add("紧邻设备维护结束后开缸，维护期间缸体视为已复位，无换色清洗");
        } else {
            why.add("排在该缸视野首段，无前序颜色，不需要换色清洗");
        }
        if (p.lateMinutes > 0) {
            d.setOutcome(R_SCHEDULED_LATE);
            why.add("⚠ 交期 " + cart.getDueTime() + "，出缸晚 " + p.lateMinutes
                    + " 分钟；已在全部适配缸可行位中选择逾期最小的位置"
                    + (cart.isUrgent() ? "（急单按最高逾期权重优先，未因省清洗被压后）" : ""));
        } else {
            d.setOutcome(R_SCHEDULED);
            why.add(cart.getDueTime() == null ? "未维护交期，按空档与清洗代价选位"
                    : "出缸 " + p.end + " 早于交期 " + cart.getDueTime() + "，不逾期");
        }
        d.setReasons(why);
        return d;
    }

    private String prevColorName(VatTimeline tl, Placement p) {
        FixedBlock prev = lastBlockEndingBefore(tl, p.washStart != null ? p.washStart : p.start);
        if (prev == null) {
            return "空档";
        }
        RuleSet.ColorFamily family = familyOf(prev.colorCode);
        return family != null ? family.getFamilyName() : prev.colorCode;
    }

    private boolean prevBlockIsMaintenance(VatTimeline tl, Placement p) {
        FixedBlock prev = lastBlockEndingBefore(tl, p.start);
        return prev != null && "MAINTENANCE".equals(prev.kind)
                && !prev.end.isBefore(p.start.minusMinutes(slotMinutes));
    }

    private CartDecision decisionOfLocked(CartInput cart, BlockInput lock) {
        CartDecision d = new CartDecision();
        d.setCartId(cart.getId());
        d.setCartCode(cart.getCartCode());
        d.setColorCode(cart.getColorCode());
        d.setColorName(cart.getColorName());
        d.setColorHex(cart.getColorHex());
        d.setFabricType(cart.getFabricType());
        d.setUrgent(cart.isUrgent());
        d.setDueTime(cart.getDueTime());
        d.setWeightKg(cart.getWeightKg());
        d.setOutcome(R_LOCKED);
        d.setLateMinutes(0);
        d.setLocked(true);
        VatInput vat = lock == null ? null : vats.stream()
                .filter(v -> Objects.equals(v.getId(), lock.getVatId())).findFirst().orElse(null);
        if (lock != null) {
            LocalDateTime s = max(lock.getStart(), horizonStart);
            LocalDateTime e = min(lock.getEnd(), horizonEnd);
            d.setVatId(lock.getVatId());
            if (vat != null) {
                d.setVatCode(vat.getVatCode());
                d.setMachineType(vat.getMachineType());
            }
            d.setStartTime(s);
            d.setEndTime(e);
            if (cart.getDueTime() != null && e.isAfter(cart.getDueTime())) {
                d.setLateMinutes(minutesBetween(cart.getDueTime(), e));
            }
        }
        List<String> reasons = new ArrayList<>();
        if (lock != null && vat != null) {
            reasons.add("该布车已人工锁定在 " + vat.getVatCode() + "，"
                    + d.getStartTime().toLocalDate() + " " + d.getStartTime().toLocalTime() + " ~ "
                    + d.getEndTime().toLocalTime() + "，承诺说明："
                    + (lock.getNote() == null || lock.getNote().isBlank() ? "无" : lock.getNote()));
        }
        reasons.add("重新计算只调整未锁定的未来区段，本车缸位与时段保持承诺不变；"
                + "其余布车的排程已绕开该固定块（含相邻换色清洗时段）");
        if (d.getLateMinutes() > 0) {
            reasons.add("⚠ 锁定出缸时间晚于交期 " + cart.getDueTime() + " 约 " + d.getLateMinutes()
                    + " 分钟，但锁定为人工承诺，系统不移动，仅提示");
        }
        reasons.add("适用规则版本：" + rule.getVersionCode());
        d.setReasons(reasons);
        return d;
    }

    private Exclusion buildExclusion(CartInput cart, List<String> reasons) {
        Exclusion e = new Exclusion();
        e.setCartId(cart.getId());
        e.setCartCode(cart.getCartCode());
        e.setColorCode(cart.getColorCode());
        e.setColorName(cart.getColorName());
        e.setColorHex(cart.getColorHex());
        e.setFabricType(cart.getFabricType());
        e.setCustomer(cart.getCustomer());
        e.setUrgent(cart.isUrgent());
        e.setDueTime(cart.getDueTime());
        e.setWeightKg(cart.getWeightKg());
        List<ExclusionItem> items = new ArrayList<>();
        if (!cart.isRecipeOk()) {
            items.add(new ExclusionItem(R_EX_RECIPE, "配方资料不完整",
                    cart.getRecipeIssue() != null ? cart.getRecipeIssue()
                            : "色号 " + cart.getColorCode() + " + 布种 " + cart.getFabricType() + " 无已发布配方"));
        }
        if (!cart.isCurveOk()) {
            items.add(new ExclusionItem(R_EX_CURVE, "工艺曲线缺失",
                    cart.getCurveIssue() != null ? cart.getCurveIssue() : "缺少绑定该配方的工艺曲线"));
        }
        for (String reason : reasons) {
            if (reason.startsWith("布种") && reason.contains("适配机型")) {
                items.add(new ExclusionItem(R_EX_MACHINE, "机型不适配", reason));
            } else if (reason.startsWith("布车折算布重")) {
                items.add(new ExclusionItem(R_EX_CAPACITY, "容量不足", reason));
            }
        }
        e.setItems(items);
        e.setSummary(String.join("；", reasons));
        return e;
    }

    private DelayedCart buildDelayed(CartInput cart, Map<Long, VatTimeline> timelines) {
        DelayedCart d = new DelayedCart();
        d.setCartId(cart.getId());
        d.setCartCode(cart.getCartCode());
        d.setColorCode(cart.getColorCode());
        d.setColorName(cart.getColorName());
        d.setColorHex(cart.getColorHex());
        d.setFabricType(cart.getFabricType());
        d.setUrgent(cart.isUrgent());
        d.setDueTime(cart.getDueTime());
        d.setWeightKg(cart.getWeightKg());
        int duration = alignedMinutes(cart.getProcessMinutes());

        LocalDateTime earliest = null;
        String earliestVat = null;
        List<String> blockers = new ArrayList<>();
        for (VatInput vat : vatOrder()) {
            if (!machineMatches(cart, vat)) {
                continue;
            }
            if (vat.getCapacityKg() != null && cart.getWeightKg() != null
                    && cart.getWeightKg().compareTo(vat.getCapacityKg()) > 0) {
                continue;
            }
            VatTimeline tl = timelines.computeIfAbsent(vat.getId(), k -> new VatTimeline());
            LocalDateTime slot = earliestAfterHorizon(tl, duration);
            if (slot != null && (earliest == null || slot.isBefore(earliest))) {
                earliest = slot;
                earliestVat = vat.getVatCode();
            }
            blockers.add(vat.getVatCode() + " 在排程视野（至 " + horizonEnd.toLocalDate()
                    + "）内无连续 " + duration + " 分钟空闲");
        }
        d.setEarliestStartTime(earliest);
        List<String> reasons = new ArrayList<>();
        if (earliest != null) {
            reasons.add("适配缸 " + earliestVat + " 最早只能在 " + earliest.toLocalDate() + " "
                    + earliest.toLocalTime() + " 开缸（已超出 "
                    + rule.getParams().getHorizonDays() + " 天排程视野）");
            if (cart.getDueTime() != null) {
                LocalDateTime earliestEnd = earliest.plusMinutes(duration);
                long late = earliestEnd.isAfter(cart.getDueTime())
                        ? minutesBetween(cart.getDueTime(), earliestEnd) : 0;
                d.setEstimatedLateMinutes(late);
                reasons.add("预计晚于交期 " + cart.getDueTime() + " 约 " + late + " 分钟");
            }
        } else {
            reasons.add("排程视野内所有适配机型缸位均被占用或处于维护窗口");
        }
        reasons.add("瓶颈：" + String.join("；", blockers));
        reasons.add("建议：插单到视野外、扩视野重算，或与设备/客户协商调整交期");
        d.setReasons(reasons);
        return d;
    }

    /** 视野结束后，某缸第一个能容纳 duration（含前序清洗）的起点，用于解释「延后到何时」 */
    private LocalDateTime earliestAfterHorizon(VatTimeline tl, int duration) {
        List<FixedBlock> seq = tl.sequence();
        LocalDateTime cursor = horizonEnd;
        for (FixedBlock b : seq) {
            if (b.end.compareTo(horizonEnd) <= 0) {
                continue;
            }
            if (b.start.isAfter(cursor)) {
                return cursor;
            }
            cursor = b.end.isAfter(cursor) ? b.end : cursor;
        }
        return cursor;
    }

    private VatTimeline timeline(Map<Long, VatTimeline> map, Long vatId) {
        return map.computeIfAbsent(vatId, k -> new VatTimeline());
    }

    private String familyCode(String colorCode) {
        RuleSet.ColorFamily f = familyByColor.get(colorCode);
        return f == null ? "?" : f.getFamilyCode();
    }

    private String machineName(String type) {
        return switch (type == null ? "" : type) {
            case "OVERFLOW" -> "溢喷机";
            case "AIRFLOW" -> "气流机";
            case "JIG" -> "卷染机";
            default -> type;
        };
    }

    private int alignedMinutes(int minutes) {
        int slot = (int) slotMinutes;
        return ((minutes + slot - 1) / slot) * slot;
    }

    private LocalDateTime ceilSlot(LocalDateTime t) {
        int slot = (int) slotMinutes;
        long minuteOfDay = t.getHour() * 60L + t.getMinute();
        long add = (slot - minuteOfDay % slot) % slot;
        LocalDateTime rounded = t.truncatedTo(ChronoUnit.HOURS).plusMinutes(t.getMinute() + add);
        if (rounded.isBefore(t)) {
            rounded = rounded.plusMinutes(slot);
        }
        return rounded.withSecond(0).withNano(0);
    }

    private LocalDateTime truncateToSlot(LocalDateTime t, int slot) {
        long minuteOfDay = t.getHour() * 60L + t.getMinute();
        long cut = minuteOfDay - minuteOfDay % slot;
        return t.truncatedTo(ChronoUnit.HOURS).plusMinutes(cut - t.getHour() * 60L)
                .withSecond(0).withNano(0);
    }

    private static long minutesBetween(LocalDateTime a, LocalDateTime b) {
        return ChronoUnit.MINUTES.between(a, b);
    }

    private static LocalDateTime max(LocalDateTime a, LocalDateTime b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDateTime min(LocalDateTime a, LocalDateTime b) {
        return a.isBefore(b) ? a : b;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    // ============================== 内部结构 ==============================

    /** 外部固定块输入（维护/锁定） */
    @Data
    public static class BlockInput {
        private Long vatId;
        private Long cartId;
        private LocalDateTime start;
        private LocalDateTime end;
        private String label;
        private String note;
        private String cartCode;
        private String colorCode;
        private String colorHex;
        private String colorName;
        private boolean urgent;
    }

    private record Interval(LocalDateTime start, LocalDateTime end) {}

    /** 缸内固定块：维护或锁定（锁定/已排作业带颜色，用于算清洗） */
    @Data
    private static class FixedBlock {
        private LocalDateTime start;
        private LocalDateTime end;
        private String kind; // MAINTENANCE / LOCK / PLACED
        private Long cartId;
        private String label;
        private String note;
        private String colorCode;
        private String colorHex;
        private String colorName;
        private String cartCode;
        private boolean urgent;
        private Integer processMinutes;
        private boolean locked;

        FixedBlock(LocalDateTime start, LocalDateTime end, String kind,
                   Long cartId, String label, String note) {
            this.start = start;
            this.end = end;
            this.kind = kind;
            this.cartId = cartId;
            this.label = label;
            this.note = note;
        }
    }

    /** 一个已排落位 */
    @Data
    private static class Placement {
        private VatTimeline tl;
        private VatInput vat;
        private CartInput cart;
        private LocalDateTime start;
        private LocalDateTime end;
        private LocalDateTime washStart;
        private WashRule washBefore;
        private WashRule washAfter;
        private long idleBefore;
        private long lateMinutes;
        private double score;
        private double expectedCost;
        private boolean openingNewVat;

        FixedBlock toBlock() {
            FixedBlock b = new FixedBlock(start, end, "PLACED", cart.getId(),
                    cart.getCartCode(), null);
            b.colorCode = cart.getColorCode();
            b.colorHex = cart.getColorHex();
            b.cartCode = cart.getCartCode();
            b.urgent = cart.isUrgent();
            b.processMinutes = cart.getProcessMinutes();
            return b;
        }
    }

    private class VatTimeline {
        private final List<FixedBlock> fixed = new ArrayList<>();
        private final List<Placement> placed = new ArrayList<>();

        private List<Interval> blockedIntervals() {
            List<FixedBlock> all = sequence();
            List<Interval> intervals = new ArrayList<>();
            for (FixedBlock b : all) {
                intervals.add(new Interval(b.start, b.end));
            }
            return intervals;
        }

        private List<FixedBlock> sequence() {
            List<FixedBlock> all = new ArrayList<>(fixed);
            for (Placement p : placed) {
                all.add(p.toBlock());
            }
            all.sort(Comparator.comparing((FixedBlock b) -> b.start).thenComparing(b -> b.end));
            return all;
        }
    }

    /** 排程结果（由服务层序列化进方案 result_json） */
    @Data
    public static class ScheduleResult {
        private LocalDateTime horizonStart;
        private LocalDateTime horizonEnd;
        private int slotMinutes;
        private Long ruleVersionId;
        private String ruleVersionCode;
        private List<TimelineItem> timeline = new ArrayList<>();
        private List<VatLoad> vatLoads = new ArrayList<>();
        private List<CartDecision> decisions = new ArrayList<>();
        private List<DelayedCart> delayed = new ArrayList<>();
        private List<Exclusion> exclusions = new ArrayList<>();
        private int totalCarts;
        private int scheduledCount;
        private int delayedCount;
        private int excludedCount;
        private int lockedCount;
        private int lateCount;
        private long totalLateMinutes;
        private long totalWashMinutes;
        private long totalWashCost;
        private long totalIdleMinutes;
        private long totalBusyMinutes;
        private long computeMs;
    }

    @Data
    public static class TimelineItem {
        private String kind; // PROCESS / WASH / MAINTENANCE / LOCK / IDLE
        private Long vatId;
        private String vatCode;
        private LocalDateTime start;
        private LocalDateTime end;
        private long minutes;
        private Long cartId;
        private String cartCode;
        private String colorCode;
        private String colorName;
        private String colorHex;
        private boolean urgent;
        private boolean locked;
        private String washCategory;
        private String washCategoryLabel;
        private int washMinutes;
        private int washCost;
        private String fromColor;
        private String toColor;
        private String note;

        static TimelineItem process(VatInput vat, FixedBlock b) {
            TimelineItem i = base(vat, b.start, b.end, "PROCESS");
            i.cartId = b.cartId;
            i.cartCode = b.cartCode;
            i.colorCode = b.colorCode;
            i.colorHex = b.colorHex;
            i.urgent = b.urgent;
            i.locked = b.locked;
            return i;
        }

        static TimelineItem lock(VatInput vat, FixedBlock b) {
            TimelineItem i = base(vat, b.start, b.end, "LOCK");
            i.cartId = b.cartId;
            i.cartCode = b.cartCode != null ? b.cartCode : b.label;
            i.colorCode = b.colorCode;
            i.colorHex = b.colorHex;
            i.colorName = b.colorName;
            i.urgent = b.urgent;
            i.note = b.note;
            i.locked = true;
            return i;
        }

        static TimelineItem maintenance(VatInput vat, FixedBlock b) {
            TimelineItem i = base(vat, b.start, b.end, "MAINTENANCE");
            i.note = b.note;
            return i;
        }

        static TimelineItem wash(VatInput vat, LocalDateTime start, LocalDateTime end,
                                 String fromColor, String toColor, WashRule wash) {
            TimelineItem i = base(vat, start, end, "WASH");
            i.fromColor = fromColor;
            i.toColor = toColor;
            i.washCategory = wash.getCategory();
            i.washCategoryLabel = wash.getCategoryLabel();
            i.washMinutes = wash.getWashMinutes();
            i.washCost = wash.getCostYuan();
            return i;
        }

        static TimelineItem idle(VatInput vat, LocalDateTime start, LocalDateTime end) {
            return base(vat, start, end, "IDLE");
        }

        private static TimelineItem base(VatInput vat, LocalDateTime start,
                                         LocalDateTime end, String kind) {
            TimelineItem i = new TimelineItem();
            i.kind = kind;
            i.vatId = vat.getId();
            i.vatCode = vat.getVatCode();
            i.start = start;
            i.end = end;
            i.minutes = ChronoUnit.MINUTES.between(start, end);
            return i;
        }
    }

    @Data
    public static class VatLoad {
        private Long vatId;
        private String vatCode;
        private String machineType;
        private BigDecimal capacityKg;
        private long busyMinutes;
        private long washMinutes;
        private long maintenanceMinutes;
        private long idleMinutes;
        /** 作业+清洗占视野比例 % */
        private double loadRate;
    }

    @Data
    public static class CartDecision {
        private String outcome; // SCHEDULED / SCHEDULED_LATE / LOCKED
        private Long cartId;
        private String cartCode;
        private String colorCode;
        private String colorName;
        private String colorHex;
        private String fabricType;
        private boolean urgent;
        private LocalDateTime dueTime;
        private BigDecimal weightKg;
        private Long vatId;
        private String vatCode;
        private String machineType;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private long lateMinutes;
        private boolean locked;
        private List<String> reasons = new ArrayList<>();
    }

    @Data
    public static class DelayedCart {
        private Long cartId;
        private String cartCode;
        private String colorCode;
        private String colorName;
        private String colorHex;
        private String fabricType;
        private boolean urgent;
        private LocalDateTime dueTime;
        private BigDecimal weightKg;
        private LocalDateTime earliestStartTime;
        private long estimatedLateMinutes;
        private List<String> reasons = new ArrayList<>();
    }

    @Data
    public static class Exclusion {
        private Long cartId;
        private String cartCode;
        private String colorCode;
        private String colorName;
        private String colorHex;
        private String fabricType;
        private String customer;
        private boolean urgent;
        private LocalDateTime dueTime;
        private BigDecimal weightKg;
        private String summary;
        private List<ExclusionItem> items = new ArrayList<>();
    }

    @Data
    public static class ExclusionItem {
        private String code;
        private String title;
        private String detail;

        public ExclusionItem(String code, String title, String detail) {
            this.code = code;
            this.title = title;
            this.detail = detail;
        }
    }
}
