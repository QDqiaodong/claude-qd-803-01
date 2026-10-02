package com.dyeing.mill.service.schedule;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dyeing.mill.common.BizException;
import com.dyeing.mill.domain.ClothCart;
import com.dyeing.mill.domain.CleaningRuleVersion;
import com.dyeing.mill.domain.DyeVat;
import com.dyeing.mill.domain.ScheduleLock;
import com.dyeing.mill.repository.ClothCartRepository;
import com.dyeing.mill.repository.DyeVatRepository;
import com.dyeing.mill.repository.ScheduleLockRepository;
import com.dyeing.mill.repository.VatMaintenanceRepository;

/**
 * 人工锁定段服务：加锁时完整校验（布车可排、机型适配、容量、维护冲突、同缸段冲突、留清洗）。
 * 锁定后重算把它当固定块，只调整未锁定未来区段。
 */
@Service
public class LockService {

    private final ScheduleLockRepository lockRepository;
    private final ClothCartRepository cartRepository;
    private final DyeVatRepository vatRepository;
    private final VatMaintenanceRepository maintenanceRepository;
    private final ScheduleService scheduleService;

    public LockService(ScheduleLockRepository lockRepository,
                       ClothCartRepository cartRepository,
                       DyeVatRepository vatRepository,
                       VatMaintenanceRepository maintenanceRepository,
                       ScheduleService scheduleService) {
        this.lockRepository = lockRepository;
        this.cartRepository = cartRepository;
        this.vatRepository = vatRepository;
        this.maintenanceRepository = maintenanceRepository;
        this.scheduleService = scheduleService;
    }

    @Transactional(readOnly = true)
    public List<ScheduleLock> list() {
        return lockRepository.findAllActive();
    }

    @Transactional
    public ScheduleLock lock(Long cartId, Long vatId, LocalDateTime start, LocalDateTime end,
                             String note, String operator) {
        if (cartId == null || vatId == null) {
            throw new BizException("请选择要锁定的布车与染缸");
        }
        if (start == null || end == null || !end.isAfter(start)) {
            throw new BizException("锁定结束时间必须晚于开始时间");
        }
        ClothCart cart = cartRepository.findById(cartId)
                .orElseThrow(() -> new BizException("布车不存在或已报废：id=" + cartId));
        String status = cart.getStatus() == null ? "PENDING" : cart.getStatus();
        if (!"PENDING".equals(status) && !"REWORK".equals(status)) {
            throw new BizException("布车 " + cart.getCartCode() + " 当前状态 " + status
                    + "，只有待排产/返修布车才能锁定排程段（排程台不改写在制/完成车）");
        }
        lockRepository.findActiveByCart(cartId).ifPresent(existing -> {
            throw new BizException("布车 " + cart.getCartCode() + " 已存在锁定段（"
                    + existing.getVatCode() + " " + existing.getStartTime() + " ~ "
                    + existing.getEndTime() + "），请先解锁再改锁");
        });
        DyeVat vat = vatRepository.findById(vatId)
                .orElseThrow(() -> new BizException("染缸不存在：id=" + vatId));

        // 用当前规则口径校验机型/容量（锁定即承诺，不能锁一台根本不能做这车的缸）
        ScheduleService.ScheduleContext currentContext = scheduleService.buildContext();
        RuleSet ruleSet = currentContext.getRuleSet();
        CleaningRuleVersion activeRule = currentContext.getRuleVersion();
        RuleSet.FabricMachine fm = ruleSet.getFabricMachines() == null ? null
                : ruleSet.getFabricMachines().stream()
                .filter(f -> Objects.equals(f.getFabricType(), cart.getFabricType()))
                .findFirst().orElse(null);
        if (fm == null || fm.getMachineTypes() == null
                || !fm.getMachineTypes().contains(vat.getMachineType())) {
            throw new BizException("机型不适配：布种 " + cart.getFabricType()
                    + (fm == null ? " 在当前规则中未维护适配机型"
                    : " 只能用 " + String.join("/", fm.getMachineTypes()))
                    + "，" + vat.getVatCode() + " 是 " + vat.getMachineType());
        }
        BigDecimal weight = cart.getMeters() == null ? null
                : cart.getMeters().multiply(ruleSet.getParams().getMetersToKg());
        if (weight != null && vat.getCapacityKg() != null
                && weight.compareTo(vat.getCapacityKg()) > 0) {
            throw new BizException("容量不足：布车折算 " + weight + "kg，" + vat.getVatCode()
                    + " 容量仅 " + vat.getCapacityKg() + "kg");
        }

        if (!maintenanceRepository.findOverlap(vatId, start, end).isEmpty()) {
            throw new BizException("锁定时段与 " + vat.getVatCode() + " 的设备维护时段重叠，无法承诺");
        }
        for (ScheduleLock other : lockRepository.findActiveByVat(vatId)) {
            if (start.isBefore(other.getEndTime()) && end.isAfter(other.getStartTime())) {
                throw new BizException("与同缸已锁定布车 " + other.getCartCode() + " 的承诺段（"
                        + other.getStartTime() + " ~ " + other.getEndTime() + "）冲突");
            }
        }

        ScheduleLock lock = new ScheduleLock();
        lock.setCartId(cart.getId());
        lock.setCartCode(cart.getCartCode());
        lock.setVatId(vat.getId());
        lock.setVatCode(vat.getVatCode());
        lock.setStartTime(start);
        lock.setEndTime(end);
        lock.setNote(note);
        lock.setRuleVersionId(activeRule.getId());
        lock.setLockedBy(operator);
        lock.setLockedTime(LocalDateTime.now());
        return lockRepository.save(lock);
    }

    @Transactional
    public void unlock(Long id) {
        ScheduleLock lock = lockRepository.findById(id)
                .orElseThrow(() -> new BizException("锁定段不存在：id=" + id));
        lock.setDeleted(1);
        lockRepository.save(lock);
    }
}
