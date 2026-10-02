<template>
  <div class="explain">
    <!-- 每车安排明细（含锁定） -->
    <div class="explain__sec">
      <div class="explain__head">
        <h4>每辆布车的安排解释（{{ decisions.length }}）</h4>
        <el-radio-group v-model="decisionFilter" size="small">
          <el-radio-button label="ALL">全部</el-radio-button>
          <el-radio-button label="LATE">逾期/急单</el-radio-button>
          <el-radio-button label="LOCK">锁定</el-radio-button>
        </el-radio-group>
      </div>
      <el-table :data="filteredDecisions" size="small" border max-height="340" row-key="cartId">
        <el-table-column prop="cartCode" label="布车" width="92" />
        <el-table-column label="色号" width="110">
          <template #default="{ row }">
            <span class="color-chip"><i :style="{ background: row.colorHex }"></i>{{ row.colorName }}</span>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="120">
          <template #default="{ row }">
            <el-tag :type="decisionTag(row.outcome)" size="small">{{ outcomeName(row.outcome) }}</el-tag>
            <el-tag v-if="row.urgent" type="danger" size="small" effect="dark" style="margin-left:4px">急</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="vatCode" label="染缸" width="70" />
        <el-table-column label="时段" width="190">
          <template #default="{ row }">
            <span v-if="row.startTime">{{ fmt(row.startTime) }} ~ {{ hm(row.endTime) }}</span>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="逾期" width="80">
          <template #default="{ row }">
            <b v-if="row.lateMinutes > 0" style="color:#c62828">{{ row.lateMinutes }}′</b>
            <span v-else>0</span>
          </template>
        </el-table-column>
        <el-table-column label="为什么这样排">
          <template #default="{ row }">
            <ul class="reasons">
              <li v-for="(r, i) in row.reasons" :key="i">{{ r }}</li>
            </ul>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="explain__cols">
      <!-- 延后 -->
      <div class="explain__sec">
        <div class="explain__head">
          <h4 class="warn">排不进视野而延后（{{ delayed.length }}）</h4>
        </div>
        <el-empty v-if="!delayed.length" description="无延后布车" :image-size="60" />
        <div v-for="d in delayed" :key="d.cartId" class="exc-card warn-card">
          <div class="exc-card__head">
            <b>{{ d.cartCode }} · {{ d.colorName }}</b>
            <el-tag v-if="d.urgent" type="danger" size="small" effect="dark">急单</el-tag>
          </div>
          <div class="exc-card__meta">交期 {{ fmt(d.dueTime) }} ｜ 最早可开缸：
            <b>{{ d.earliestStartTime ? fmt(d.earliestStartTime) : '视野外待定' }}</b>
            <span v-if="d.estimatedLateMinutes" style="color:#c62828">（预计逾期 {{ d.estimatedLateMinutes }}′）</span>
          </div>
          <ul class="reasons"><li v-for="(r, i) in d.reasons" :key="i">{{ r }}</li></ul>
        </div>
      </div>

      <!-- 排除 -->
      <div class="explain__sec">
        <div class="explain__head">
          <h4 class="danger">资料/容量/机型问题排除（{{ excluded.length }}）</h4>
        </div>
        <el-empty v-if="!excluded.length" description="无排除布车" :image-size="60" />
        <div v-for="e in excluded" :key="e.cartId" class="exc-card danger-card">
          <div class="exc-card__head">
            <b>{{ e.cartCode }} · {{ e.colorName }}（{{ e.fabricType }}）</b>
            <el-tag v-if="e.urgent" type="danger" size="small" effect="dark">急单</el-tag>
          </div>
          <div class="exc-card__meta">客户 {{ e.customer }} ｜ 折算 {{ e.weightKg }}kg ｜ 交期 {{ fmt(e.dueTime) }}</div>
          <ul class="reasons">
            <li v-for="item in e.items" :key="item.code" class="exc-item">
              <el-tag :type="itemTag(item.code)" size="small">{{ item.title }}</el-tag>
              <span>{{ item.detail }}</span>
            </li>
          </ul>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'

const props = defineProps({
  result: { type: Object, default: () => ({}) }
})

const decisionFilter = ref('ALL')
const decisions = computed(() => props.result.decisions || [])
const delayed = computed(() => props.result.delayed || [])
const excluded = computed(() => props.result.exclusions || [])

const filteredDecisions = computed(() => {
  if (decisionFilter.value === 'LATE') {
    return decisions.value.filter((d) => d.lateMinutes > 0 || d.urgent)
  }
  if (decisionFilter.value === 'LOCK') {
    return decisions.value.filter((d) => d.outcome === 'LOCKED')
  }
  return decisions.value
})

function fmt(t) { return t ? String(t).replace('T', ' ').slice(0, 16) : '—' }
function hm(t) { return t ? String(t).slice(11, 16) : '' }
function outcomeName(o) {
  return { SCHEDULED: '已排入', SCHEDULED_LATE: '排入但逾期', LOCKED: '人工锁定', DELAYED: '延后', EXCLUDED: '排除' }[o] || o
}
function decisionTag(o) {
  return { SCHEDULED: 'success', SCHEDULED_LATE: 'danger', LOCKED: 'primary' }[o] || 'info'
}
function itemTag(code) {
  return { EXCLUDED_RECIPE: 'danger', EXCLUDED_CURVE: 'warning', EXCLUDED_CAPACITY: 'danger', EXCLUDED_MACHINE: 'info' }[code] || ''
}
</script>

<style scoped>
.explain__sec { background: #fff; border: 1px solid var(--mill-line); border-radius: 8px; padding: 10px 12px; margin-bottom: 12px; }
.explain__cols { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
@media (max-width: 1280px) { .explain__cols { grid-template-columns: 1fr; } }
.explain__head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.explain__head h4 { margin: 0; font-size: 14px; color: var(--mill-primary-deep); }
h4.warn { color: #b26a00; }
h4.danger { color: #c62828; }
.color-chip { display: inline-flex; align-items: center; gap: 5px; }
.color-chip i { width: 12px; height: 12px; border-radius: 3px; display: inline-block; border: 1px solid rgba(0,0,0,.15); }
.reasons { margin: 2px 0; padding-left: 16px; }
.reasons li { font-size: 12px; color: var(--mill-text); line-height: 1.55; }
.exc-item { list-style: none; display: flex; gap: 6px; align-items: flex-start; }
.exc-item .el-tag { flex: 0 0 auto; margin-top: 1px; }
.exc-card { border-radius: 6px; padding: 8px 10px; margin-bottom: 8px; }
.warn-card { background: #fff8ec; border: 1px solid #f3d9a8; }
.danger-card { background: #fdf3f3; border: 1px solid #f1c4c4; }
.exc-card__head { display: flex; justify-content: space-between; align-items: center; }
.exc-card__meta { font-size: 12px; color: var(--mill-muted); margin: 3px 0; }
</style>
