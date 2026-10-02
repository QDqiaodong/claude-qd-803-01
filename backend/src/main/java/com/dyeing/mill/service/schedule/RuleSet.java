package com.dyeing.mill.service.schedule;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/**
 * 换色排程规则口径（某一规则版本解析后的不可变对象）。
 * 引擎只依赖该口径，因此旧方案用旧版本复算时天然得到当时的清洗时长/成本与解释。
 */
@Data
public class RuleSet {

    private long versionId;
    private String versionCode;
    private Matrix matrix;
    private List<FabricMachine> fabricMachines;
    private Params params;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Matrix {
        private List<ColorFamily> colorFamilies;
        private List<CategoryDefault> categoryDefaults;
        private List<MatrixRow> rows;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ColorFamily {
        private String familyCode;
        private String familyName;
        private List<String> colorCodes;
        /** LIGHT 浅 / MID 中 / DEEP 深 */
        private String shade;
        private boolean special;
        private String specialName;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CategoryDefault {
        /** SAME 同色续染 / LIGHT_TO_DEEP 浅转深 / DEEP_TO_LIGHT 深转浅 / SPECIAL 特殊染料切换 */
        private String category;
        private int washMinutes;
        private int costYuan;
        private String label;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MatrixRow {
        private String fromFamily;
        private String toFamily;
        private String category;
        private Integer washMinutes;
        private Integer costYuan;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FabricMachine {
        private String fabricType;
        private String fabricName;
        private List<String> machineTypes;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Params {
        private BigDecimal metersToKg = new BigDecimal("0.18");
        private int slotMinutes = 15;
        private int horizonDays = 5;
        private String horizonStart;
        private double urgentLatenessWeight = 60.0;
        private double latenessWeight = 12.0;
        private double idleWeight = 0.35;
        private double washCostWeight = 1.0;
        /** 新开缸（该缸首个染色作业）固定准备成本：高于同色续染、低于浅转深，引导负荷集中 */
        private double newVatPenaltyYuan = 120.0;
        private boolean washGapIdleFree = true;
        private String notes;
    }
}
