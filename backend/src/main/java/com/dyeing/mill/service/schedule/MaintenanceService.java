package com.dyeing.mill.service.schedule;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dyeing.mill.common.BizException;
import com.dyeing.mill.domain.DyeVat;
import com.dyeing.mill.domain.ScheduleLock;
import com.dyeing.mill.domain.VatMaintenance;
import com.dyeing.mill.repository.DyeVatRepository;
import com.dyeing.mill.repository.ScheduleLockRepository;
import com.dyeing.mill.repository.VatMaintenanceRepository;

/**
 * 设备维护时段维护：重叠校验 + 与人工锁定段冲突校验（已承诺的锁定不能被新维护悄悄破坏）。
 */
@Service
public class MaintenanceService {

    private final VatMaintenanceRepository maintenanceRepository;
    private final ScheduleLockRepository lockRepository;
    private final DyeVatRepository vatRepository;

    public MaintenanceService(VatMaintenanceRepository maintenanceRepository,
                              ScheduleLockRepository lockRepository,
                              DyeVatRepository vatRepository) {
        this.maintenanceRepository = maintenanceRepository;
        this.lockRepository = lockRepository;
        this.vatRepository = vatRepository;
    }

    @Transactional(readOnly = true)
    public List<VatMaintenance> list() {
        return maintenanceRepository.findAllActive();
    }

    @Transactional
    public VatMaintenance create(Long vatId, LocalDateTime start, LocalDateTime end, String reason) {
        validate(vatId, start, end, reason, null);
        DyeVat vat = vatRepository.findById(vatId)
                .orElseThrow(() -> new BizException("染缸不存在：id=" + vatId));
        VatMaintenance m = new VatMaintenance();
        m.setVatId(vat.getId());
        m.setVatCode(vat.getVatCode());
        m.setStartTime(start);
        m.setEndTime(end);
        m.setReason(reason);
        m.setCreateTime(LocalDateTime.now());
        return maintenanceRepository.save(m);
    }

    @Transactional
    public VatMaintenance update(Long id, LocalDateTime start, LocalDateTime end, String reason) {
        VatMaintenance m = maintenanceRepository.findById(id)
                .orElseThrow(() -> new BizException("维护时段不存在：id=" + id));
        validate(m.getVatId(), start, end, reason, id);
        m.setStartTime(start);
        m.setEndTime(end);
        m.setReason(reason);
        return maintenanceRepository.save(m);
    }

    @Transactional
    public void delete(Long id) {
        VatMaintenance m = maintenanceRepository.findById(id)
                .orElseThrow(() -> new BizException("维护时段不存在：id=" + id));
        m.setDeleted(1);
        maintenanceRepository.save(m);
    }

    private void validate(Long vatId, LocalDateTime start, LocalDateTime end, String reason, Long excludeId) {
        if (vatId == null) {
            throw new BizException("请选择染缸");
        }
        if (start == null || end == null || !end.isAfter(start)) {
            throw new BizException("维护结束时间必须晚于开始时间");
        }
        if (reason == null || reason.isBlank()) {
            throw new BizException("请填写维护原因");
        }
        for (VatMaintenance other : maintenanceRepository.findOverlap(vatId, start, end)) {
            if (excludeId == null || !other.getId().equals(excludeId)) {
                throw new BizException("该染缸在 " + other.getStartTime() + " ~ " + other.getEndTime()
                        + " 已有维护时段（" + other.getReason() + "），不能重叠登记");
            }
        }
        for (ScheduleLock lock : lockRepository.findActiveByVat(vatId)) {
            if (start.isBefore(lock.getEndTime()) && end.isAfter(lock.getStartTime())) {
                throw new BizException("维护时段与已承诺的人工锁定段冲突：布车 " + lock.getCartCode()
                        + " 已锁定 " + lock.getStartTime() + " ~ " + lock.getEndTime()
                        + "，请先与调度员确认并解除/改锁该段");
            }
        }
    }
}
