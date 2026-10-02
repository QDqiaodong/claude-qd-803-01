<template>
  <div class="plans">
    <div class="plans__bar">
      <el-button type="primary" plain @click="$emit('save')">留存当前草案为新方案</el-button>
      <span class="plans__tip">草案随输入重算自动覆盖；只有留存快照可被选为「车间参考」。方案不回写布车或开缸执行单。</span>
    </div>

    <el-table :data="plans" size="small" border>
      <el-table-column label="方案" min-width="200">
        <template #default="{ row }">
          <b>{{ row.name }}</b>
          <div class="plans__code">{{ row.planCode }}</div>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="100">
        <template #default="{ row }">
          <el-tag :type="row.planKind === 'DRAFT' ? 'info' : ''" size="small">
            {{ row.planKind === 'DRAFT' ? '工作草案' : '留存快照' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag v-if="row.status === 'REFERENCE'" type="success" effect="dark" size="small">★ 车间参考</el-tag>
          <span v-else class="plans__muted">普通</span>
        </template>
      </el-table-column>
      <el-table-column prop="ruleVersionCode" label="规则版本" width="130" />
      <el-table-column label="排/锁/延/排" width="120">
        <template #default="{ row }">
          {{ row.scheduledCount }} / {{ row.lockedCount }} / {{ row.delayedCount }} / {{ row.excludedCount }}
        </template>
      </el-table-column>
      <el-table-column label="生成时间" width="160">
        <template #default="{ row }">{{ fmt(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="300">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="$emit('view', row)">查看</el-button>
          <el-button link type="success" size="small" :disabled="row.planKind === 'DRAFT'"
                     @click="$emit('reference', row)">选为参考</el-button>
          <el-button link type="warning" size="small" @click="$emit('replay', row)">按原版本复算</el-button>
          <el-button link type="danger" size="small" :disabled="row.planKind === 'DRAFT'"
                     @click="$emit('remove', row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="plans__diff">
      <h4>方案差异对比</h4>
      <div class="plans__diff-bar">
        <el-select v-model="planA" placeholder="方案 A（基准）" size="small" style="width: 240px">
          <el-option v-for="p in plans" :key="p.id" :label="`${p.planCode} ${p.name}`" :value="p.id" />
        </el-select>
        <span>vs</span>
        <el-select v-model="planB" placeholder="方案 B" size="small" style="width: 240px">
          <el-option v-for="p in plans" :key="p.id" :label="`${p.planCode} ${p.name}`" :value="p.id" />
        </el-select>
        <el-button size="small" type="primary" :disabled="!planA || !planB" @click="runDiff">对比</el-button>
      </div>

      <template v-if="diff">
        <div class="plans__metrics">
          <div class="m" v-for="m in metricCards" :key="m.key">
            <span>{{ m.label }}</span>
            <b>{{ m.a }}</b>
            <i :class="deltaClass(m.delta)">{{ deltaText(m.delta, m.unit) }}</i>
            <b>{{ m.b }}</b>
          </div>
        </div>
        <el-table :data="diffRows" size="small" border max-height="320">
          <el-table-column prop="cartCode" label="布车" width="100" />
          <el-table-column label="方案 A" width="210">
            <template #default="{ row }">
              {{ outcomeName(row.outcomeA) }} {{ row.vatA || '' }} {{ fmt(row.startA) }}
            </template>
          </el-table-column>
          <el-table-column label="方案 B" width="210">
            <template #default="{ row }">
              {{ outcomeName(row.outcomeB) }} {{ row.vatB || '' }} {{ fmt(row.startB) }}
            </template>
          </el-table-column>
          <el-table-column label="变化" width="90">
            <template #default="{ row }">
              <el-tag :type="row.changed ? 'danger' : 'success'" size="small">
                {{ row.changed ? '有调整' : '一致' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { diffSchedulePlans } from '../../api'

const props = defineProps({
  plans: { type: Array, default: () => [] }
})
const emit = defineEmits(['view', 'reference', 'replay', 'remove'])

const planA = ref(null)
const planB = ref(null)
const diff = ref(null)

watch(() => props.plans.map((p) => p.id).join(','), () => {
  if (!planA.value || !props.plans.some((p) => p.id === planA.value)) {
    planA.value = props.plans.find((p) => p.planKind === 'DRAFT')?.id || props.plans[0]?.id
  }
  if (!planB.value || !props.plans.some((p) => p.id === planB.value)) {
    planB.value = props.plans.find((p) => p.status === 'REFERENCE')?.id
      || props.plans.find((p) => p.planKind === 'SNAPSHOT')?.id || null
  }
})

async function runDiff() {
  diff.value = (await diffSchedulePlans(planA.value, planB.value)).data
}
const diffRows = computed(() => (diff.value ? diff.value.rows.filter((r) => r.changed) : []))
const metricCards = computed(() => {
  if (!diff.value) return []
  const d = diff.value.metricDeltas
  const keys = [
    ['scheduledCount', '已排入（含锁）', ''],
    ['delayedCount', '延后', ''],
    ['excludedCount', '排除', ''],
    ['lateCount', '逾期车数', ''],
    ['totalLateMinutes', '逾期总分钟', '′'],
    ['totalWashMinutes', '清洗总时长', '′'],
    ['totalWashCost', '清洗总成本', '元'],
    ['totalIdleMinutes', '空档总时长', '′']
  ]
  return keys.map(([key, label, unit]) => ({
    key, label, unit,
    a: d[key]?.a, b: d[key]?.b, delta: d[key]?.delta ?? 0
  }))
})
function deltaClass(delta) {
  return delta > 0 ? 'up' : delta < 0 ? 'down' : 'flat'
}
function deltaText(delta, unit) {
  if (delta === 0) return '= 0'
  return (delta > 0 ? '+' : '') + Math.round(delta * 10) / 10 + unit
}
function fmt(t) { return t ? String(t).replace('T', ' ').slice(0, 16) : '' }
function outcomeName(o) {
  return { SCHEDULED: '排入', SCHEDULED_LATE: '排入(逾)', LOCKED: '锁定', DELAYED: '延后', EXCLUDED: '排除', NOT_PRESENT: '未出现' }[o] || o
}
</script>

<style scoped>
.plans__bar { display: flex; align-items: center; gap: 12px; margin-bottom: 10px; }
.plans__tip { font-size: 12px; color: var(--mill-muted); }
.plans__code { font-size: 11px; color: var(--mill-muted); }
.plans__muted { color: var(--mill-muted); font-size: 12px; }
.plans__diff { margin-top: 16px; background: #fff; border: 1px solid var(--mill-line); border-radius: 8px; padding: 10px 12px; }
.plans__diff h4 { margin: 0 0 8px; font-size: 14px; color: var(--mill-primary-deep); }
.plans__diff-bar { display: flex; gap: 8px; align-items: center; margin-bottom: 10px; }
.plans__metrics { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; margin-bottom: 10px; }
.m { background: #faf8fd; border: 1px solid var(--mill-line); border-radius: 6px; padding: 6px 10px; font-size: 12px;
     display: grid; grid-template-columns: 1fr auto auto; gap: 2px 8px; align-items: center; }
.m span { color: var(--mill-muted); grid-column: 1 / -1; }
.m i { font-style: normal; font-size: 11px; }
.m i.up { color: #c62828; }
.m i.down { color: #2e7d32; }
.m i.flat { color: var(--mill-muted); }
</style>
