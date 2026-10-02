<template>
  <div class="rules">
    <el-alert type="info" :closable="false" show-icon class="rules__alert"
      title="清洗矩阵整体版本化：保存后生成新的 ACTIVE 版本，旧版本自动归档。新方案按新口径计算，已留存的旧方案仍可用当时版本复算并显示原解释。" />

    <div class="rules__sec">
      <div class="rules__sec-head">
        <h4>① 四类切换默认口径</h4>
        <span>顺序敏感：深转浅清洗最久；特殊染料族进出按「特殊染料切换」</span>
      </div>
      <el-table :data="defaults" size="small" border>
        <el-table-column prop="category" label="分类" width="170">
          <template #default="{ row }">
            <el-tag :type="catTag(row.category)" effect="plain">{{ row.label }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="清洗时长（分钟）" width="200">
          <template #default="{ row }">
            <el-input-number v-model="row.washMinutes" :min="0" :max="1440" :step="5" size="small" />
          </template>
        </el-table-column>
        <el-table-column label="清洗成本（元/次）" width="200">
          <template #default="{ row }">
            <el-input-number v-model="row.costYuan" :min="0" :max="100000" :step="10" size="small" />
          </template>
        </el-table-column>
        <el-table-column prop="category" label="口径码" />
      </el-table>
    </div>

    <div class="rules__sec">
      <div class="rules__sec-head">
        <h4>② 颜色族（色号 → 族 / 深浅档 / 特殊染料）</h4>
        <span>深浅档：LIGHT 浅 · MID 中 · DEEP 深；跨族且任一端勾特殊 → 特殊染料切换</span>
      </div>
      <el-table :data="families" size="small" border>
        <el-table-column prop="familyName" label="颜色族" width="120" />
        <el-table-column prop="familyCode" label="族编码" width="110" />
        <el-table-column label="深浅档" width="110">
          <template #default="{ row }">
            <el-select v-model="row.shade" size="small">
              <el-option label="浅色 LIGHT" value="LIGHT" />
              <el-option label="中档 MID" value="MID" />
              <el-option label="深色 DEEP" value="DEEP" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="特殊染料" width="200">
          <template #default="{ row }">
            <el-switch v-model="row.special" />
            <el-input v-if="row.special" v-model="row.specialName" size="small"
                      placeholder="如 阳离子染料" style="width: 120px; margin-left: 8px" />
          </template>
        </el-table-column>
        <el-table-column label="包含色号">
          <template #default="{ row }">
            <el-select v-model="row.colorCodes" multiple size="small"
                       style="width: 100%" placeholder="选择色号">
              <el-option v-for="c in allColors" :key="c.colorCode"
                         :label="`${c.colorCode} ${c.colorName}`" :value="c.colorCode" />
            </el-select>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="rules__sec">
      <div class="rules__sec-head">
        <h4>③ 颜色族对专用覆写（可选，优先于深浅默认值）</h4>
        <div>
          <el-button size="small" @click="addRow">新增覆写</el-button>
        </div>
      </div>
      <el-table :data="rows" size="small" border>
        <el-table-column label="前序族" width="150">
          <template #default="{ row }">
            <el-select v-model="row.fromFamily" size="small">
              <el-option v-for="f in families" :key="f.familyCode" :label="f.familyName" :value="f.familyCode" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="→ 后序族" width="150">
          <template #default="{ row }">
            <el-select v-model="row.toFamily" size="small">
              <el-option v-for="f in families" :key="f.familyCode" :label="f.familyName" :value="f.familyCode" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="适用分类" width="170">
          <template #default="{ row }">
            <el-select v-model="row.category" size="small">
              <el-option label="同色续染" value="SAME" />
              <el-option label="浅转深" value="LIGHT_TO_DEEP" />
              <el-option label="深转浅" value="DEEP_TO_LIGHT" />
              <el-option label="特殊染料切换" value="SPECIAL" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="分钟" width="130">
          <template #default="{ row }">
            <el-input-number v-model="row.washMinutes" :min="0" :max="1440" :step="5" size="small" />
          </template>
        </el-table-column>
        <el-table-column label="成本（元）" width="140">
          <template #default="{ row }">
            <el-input-number v-model="row.costYuan" :min="0" :max="100000" :step="10" size="small" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80">
          <template #default="{ $index }">
            <el-button link type="danger" size="small" @click="rows.splice($index, 1)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="rules__sec">
      <div class="rules__sec-head">
        <h4>④ 布种 → 适配机型矩阵</h4>
      </div>
      <el-table :data="fabricMachines" size="small" border>
        <el-table-column prop="fabricType" label="布种" width="130" />
        <el-table-column prop="fabricName" label="名称" width="140" />
        <el-table-column label="允许机型（机型不适配的布车会被单列排除）">
          <template #default="{ row }">
            <el-checkbox-group v-model="row.machineTypes">
              <el-checkbox value="OVERFLOW">溢喷 OVERFLOW</el-checkbox>
              <el-checkbox value="AIRFLOW">气流 AIRFLOW</el-checkbox>
              <el-checkbox value="JIG">卷染 JIG</el-checkbox>
            </el-checkbox-group>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="rules__sec">
      <div class="rules__sec-head"><h4>⑤ 排程参数</h4></div>
      <div class="rules__params">
        <label>米→kg 折算系数
          <el-input-number v-model="params.metersToKg" :step="0.01" :min="0.01" :max="1" :precision="2" />
        </label>
        <label>时槽粒度（分钟）
          <el-input-number v-model="params.slotMinutes" :step="5" :min="5" :max="60" />
        </label>
        <label>排程视野（天）
          <el-input-number v-model="params.horizonDays" :min="1" :max="30" />
        </label>
        <label>视野起点
          <el-date-picker v-model="params.horizonStart" type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss" format="MM-dd HH:mm" :clearable="false" />
        </label>
        <label>急单逾期权重（元/分钟）
          <el-input-number v-model="params.urgentLatenessWeight" :step="1" :min="0" :precision="1" />
        </label>
        <label>普通逾期权重（元/分钟）
          <el-input-number v-model="params.latenessWeight" :step="1" :min="0" :precision="2" />
        </label>
        <label>空档权重（元/分钟）
          <el-input-number v-model="params.idleWeight" :step="0.05" :min="0" :precision="2" />
        </label>
        <label>清洗成本权重
          <el-input-number v-model="params.washCostWeight" :step="0.1" :min="0" :precision="2" />
        </label>
      </div>
      <el-input v-model="params.notes" type="textarea" :rows="2" placeholder="口径备注" />
    </div>

    <div class="rules__foot">
      <el-input v-model="remark" placeholder="本次调整说明（如：深转浅改为 100 分钟）"
                style="width: 360px" size="default" />
      <el-button type="primary" :loading="publishing" @click="$emit('publish', buildPayload())">
        保存为新版本并生效
      </el-button>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'

const props = defineProps({
  initial: { type: Object, required: true },
  allColors: { type: Array, default: () => [] },
  publishing: { type: Boolean, default: false }
})
defineEmits(['publish'])

const families = ref(JSON.parse(JSON.stringify(props.initial.matrix.colorFamilies || [])))
const defaults = ref(JSON.parse(JSON.stringify(props.initial.matrix.categoryDefaults || [])))
const rows = ref(JSON.parse(JSON.stringify(props.initial.matrix.rows || [])))
const fabricMachines = ref(JSON.parse(JSON.stringify(props.initial.fabricMachines || [])))
const params = ref(JSON.parse(JSON.stringify(props.initial.params || {})))
const remark = ref('')

function addRow() {
  rows.value.push({
    fromFamily: families.value[0]?.familyCode,
    toFamily: families.value[1]?.familyCode || families.value[0]?.familyCode,
    category: 'LIGHT_TO_DEEP',
    washMinutes: 30,
    costYuan: 260
  })
}
function buildPayload() {
  return {
    matrixJson: JSON.stringify({ colorFamilies: families.value, categoryDefaults: defaults.value, rows: rows.value }),
    fabricMachineJson: JSON.stringify(fabricMachines.value),
    paramsJson: JSON.stringify(params.value),
    remark: remark.value
  }
}
function catTag(c) {
  return { SAME: 'success', LIGHT_TO_DEEP: 'warning', DEEP_TO_LIGHT: 'danger', SPECIAL: 'info' }[c] || ''
}
</script>

<style scoped>
.rules__sec { background: #fff; border: 1px solid var(--mill-line); border-radius: 8px; padding: 10px 12px; margin-bottom: 12px; }
.rules__sec-head { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; margin-bottom: 8px; }
.rules__sec-head h4 { margin: 0; font-size: 14px; color: var(--mill-primary-deep); }
.rules__sec-head span { font-size: 12px; color: var(--mill-muted); }
.rules__alert { margin-bottom: 12px; }
.rules__params { display: flex; flex-wrap: wrap; gap: 10px 22px; margin-bottom: 10px; }
.rules__params label { font-size: 12px; color: var(--mill-muted); display: flex; flex-direction: column; gap: 4px; }
.rules__foot { display: flex; gap: 10px; align-items: center; background: #f3ebfa; border-radius: 8px; padding: 10px 12px; }
</style>
