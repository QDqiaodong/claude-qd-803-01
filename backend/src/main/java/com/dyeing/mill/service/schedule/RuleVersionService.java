package com.dyeing.mill.service.schedule;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dyeing.mill.common.BizException;
import com.dyeing.mill.domain.CleaningRuleVersion;
import com.dyeing.mill.repository.CleaningRuleVersionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 清洗规则版本服务：矩阵/机型矩阵/参数整体保存为新版本并自动归档旧版本。
 * 新版本只影响之后产生的方案；旧方案持有 ruleVersionId，复算时取旧版本口径。
 */
@Service
public class RuleVersionService {

    private final CleaningRuleVersionRepository repository;
    private final ObjectMapper objectMapper;

    public RuleVersionService(CleaningRuleVersionRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<CleaningRuleVersion> list() {
        return repository.findAllOrdered();
    }

    @Transactional(readOnly = true)
    public CleaningRuleVersion active() {
        return repository.findActive()
                .orElseThrow(() -> new BizException("系统还没有生效的换色清洗规则版本，请先维护清洗矩阵"));
    }

    @Transactional(readOnly = true)
    public CleaningRuleVersion get(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BizException("规则版本不存在：id=" + id));
    }

    /**
     * 保存当前矩阵为新版本：旧 ACTIVE 置 ARCHIVED，新版本 ACTIVE。
     * 入参为三段 JSON 字符串（页面表单提交对象，由 Controller 转 JSON）。
     */
    @Transactional
    public CleaningRuleVersion publish(String matrixJson, String fabricMachineJson,
                                       String paramsJson, String remark, String operator) {
        validateJson(matrixJson, "清洗矩阵");
        validateJson(fabricMachineJson, "布种机型矩阵");
        validateJson(paramsJson, "排程参数");
        RuleSet parsed = parse(matrixJson, fabricMachineJson, paramsJson, null, null);
        validateRuleSet(parsed);

        repository.findActive().ifPresent(old -> {
            old.setStatus("ARCHIVED");
            old.setArchivedTime(LocalDateTime.now());
            repository.save(old);
        });
        CleaningRuleVersion version = new CleaningRuleVersion();
        version.setVersionCode(generateVersionCode());
        version.setStatus("ACTIVE");
        version.setMatrixJson(matrixJson);
        version.setFabricMachineJson(fabricMachineJson);
        version.setParamsJson(paramsJson);
        version.setRemark(remark);
        version.setCreatedBy(operator);
        version.setCreateTime(LocalDateTime.now());
        return repository.save(version);
    }

    /** 把某版本解析为引擎口径（旧方案复算时必须显式传 id，不能误用当前 ACTIVE） */
    public RuleSet loadRuleSet(Long versionId) {
        CleaningRuleVersion version = versionId == null ? active() : get(versionId);
        return parse(version.getMatrixJson(), version.getFabricMachineJson(),
                version.getParamsJson(), version.getId(), version.getVersionCode());
    }

    private RuleSet parse(String matrixJson, String fabricMachineJson, String paramsJson,
                          Long versionId, String versionCode) {
        try {
            RuleSet ruleSet = new RuleSet();
            ruleSet.setMatrix(objectMapper.readValue(matrixJson, RuleSet.Matrix.class));
            ruleSet.setFabricMachines(objectMapper.readValue(fabricMachineJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, RuleSet.FabricMachine.class)));
            ruleSet.setParams(objectMapper.readValue(paramsJson, RuleSet.Params.class));
            ruleSet.setVersionId(versionId);
            ruleSet.setVersionCode(versionCode);
            return ruleSet;
        } catch (Exception e) {
            throw new BizException("规则 JSON 解析失败：" + e.getMessage());
        }
    }

    private void validateJson(String json, String label) {
        if (json == null || json.isBlank()) {
            throw new BizException(label + "内容不能为空");
        }
        try {
            objectMapper.readTree(json);
        } catch (Exception e) {
            throw new BizException(label + "不是合法 JSON：" + e.getMessage());
        }
    }

    private void validateRuleSet(RuleSet ruleSet) {
        if (ruleSet.getMatrix() == null || ruleSet.getMatrix().getCategoryDefaults() == null
                || ruleSet.getMatrix().getCategoryDefaults().size() < 4) {
            throw new BizException("清洗矩阵必须包含同色续染/浅转深/深转浅/特殊染料切换四类默认口径");
        }
        for (String category : List.of("SAME", "LIGHT_TO_DEEP", "DEEP_TO_LIGHT", "SPECIAL")) {
            boolean exists = ruleSet.getMatrix().getCategoryDefaults().stream()
                    .anyMatch(d -> category.equals(d.getCategory()));
            if (!exists) {
                throw new BizException("清洗矩阵缺少分类默认值：" + categoryName(category));
            }
        }
        if (ruleSet.getFabricMachines() == null || ruleSet.getFabricMachines().isEmpty()) {
            throw new BizException("布种机型适配矩阵不能为空");
        }
        if (ruleSet.getParams().getSlotMinutes() <= 0) {
            throw new BizException("时槽粒度必须为正整数分钟");
        }
        if (ruleSet.getParams().getUrgentLatenessWeight() < ruleSet.getParams().getWashCostWeight()) {
            throw new BizException("急单逾期权重不得低于清洗成本权重，防止为省清洗压急单");
        }
    }

    private String generateVersionCode() {
        return "RV-" + LocalDateTime.now().getYear()
                + String.format("%02d", LocalDateTime.now().getMonthValue())
                + String.format("%02d", LocalDateTime.now().getDayOfMonth())
                + "-" + String.format("%03d", (repository.count() + 1));
    }

    private String categoryName(String category) {
        return switch (category) {
            case "SAME" -> "同色续染";
            case "LIGHT_TO_DEEP" -> "浅转深";
            case "DEEP_TO_LIGHT" -> "深转浅";
            case "SPECIAL" -> "特殊染料切换";
            default -> category;
        };
    }
}
