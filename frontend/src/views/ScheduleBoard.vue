<template>
  <div class="board">
    <!-- 顶部工具条 -->
    <div class="board__toolbar">
      <div class="board__rule">
        规则口径
        <el-tag size="small" type="warning">{{ ruleVersionCode }}</el-tag>
        <span class="board__horizon">视野 {{ fmt(context.horizonStart) }} ~ {{ fmtDate(context.horizonEnd) }}</span>
      </div>
      <div class="board__actions">
        <el-tag v-if="job" :type="jobTagType" size="small" effect="plain" class="board__jobtag">
          {{ jobText }}
        </el-tag>
        <el-button type="primary" :loading="jobRunning" @click="recompute">
          {{ jobRunning ? '批量重算中…' : '批量重算' }}
        </el-button>
        <el-button @click="saveSnapshot">留存方案</el-button>
      </div>
    </div>

    <!-- KPI -->
    <div class="kpis">
      <div class="kpi"><span>待排布车</span><b>{{ stats.totalCarts ?? 0 }}</b></div>
      <div class="kpi ok"><span>已排入</span><b>{{ stats.scheduledCount ?? 0 }}</b><i>锁定 {{ stats.lockedCount ?? 0 }}</i></div>
      <div class="kpi danger"><span>逾期车</span><b>{{ stats.lateCount ?? 0 }}</b><i>共 {{ stats.totalLateMinutes ?? 0 }} 分钟</i></div>
      <div class="kpi warn"><span>延后</span><b>{{ stats.delayedCount ?? 0 }}</b><i>视野内容不下</i></div>
      <div class="kpi danger"><span>排除</span><b>{{ stats.excludedCount ?? 0 }}</b><i>资料/容量/机型</i></div>
      <div class="kpi"><span>清洗占时</span><b>{{ formatHours(stats.totalWashMinutes) }}</b><i>约 {{ stats.totalWashCost ?? 0 }} 元</i></div>
      <div class="kpi"><span>空档</span><b>{{ formatHours(stats.totalIdleMinutes) }}</b><i>全缸合计</i></div>
      <div class="kpi"><span>计算耗时</span><b>{{ stats.computeMs ?? 0 }}ms</b></div>
    </div>

    <el-tabs v-model="activeTab" class="board__tabs">
      <!-- 时间轴 -->
      <el-tab-pane label="跨缸时间轴" name="timeline">
        <ScheduleGantt v-if="result" :timeline="result.timeline" :vats="context.vats"
                       :loads="result.vatLoads" :horizon-start="context.horizonStart"
                       :horizon-end="context.horizonEnd" @select-item="showItem" />
        <el-empty v-else description="尚无排程结果，点击右上角「批量重算」" />

        <div class="board__row-title">染缸负载与清洗占时</div>
        <div class="loads">
          <div v-for="l in result?.vatLoads || []" :key="l.vatId" class="load-card">
            <div class="load-card__head">
              <b>{{ l.vatCode }}</b>
              <span>{{ machineName(l.machineType) }} · {{ l.capacityKg }}kg</span>
            </div>
            <div class="load-bar">
              <span class="load-bar__busy" :style="{ width: barPct(l.busyMinutes) }" title="染色"></span>
              <span class="load-bar__wash" :style="{ left: barPct(l.busyMinutes), width: barPct(l.washMinutes) }" title="清洗"></span>
              <span class="load-bar__maint"
                    :style="{ left: barPct(l.busyMinutes + l.washMinutes), width: barPct(l.maintenanceMinutes) }" title="维护"></span>
            </div>
            <div class="load-card__nums">
              <span>染色 {{ Math.round(l.busyMinutes / 60 * 10) / 10 }}h</span>
              <span class="wash">清洗 {{ Math.round(l.washMinutes / 60 * 10) / 10 }}h</span>
              <span class="maint">维护 {{ Math.round(l.maintenanceMinutes / 60 * 10) / 10 }}h</span>
              <span>空档 {{ Math.round(l.idleMinutes / 60 * 10) / 10 }}h</span>
            </div>
          </div>
        </div>
      </el-tab-pane>

      <!-- 解释 -->
      <el-tab-pane :label="`安排/延后/排除 (${(result?.exclusions.length || 0) + (result?.delayed.length || 0)})`" name="exceptions">
        <DecisionPanel v-if="result" :result="result" />
        <el-empty v-else description="尚无排程结果" />
      </el-tab-pane>

      <!-- 待排车与交期/急单 -->
      <el-tab-pane label="交期与急单输入" name="carts">
        <el-alert type="info" :closable="false" show-icon
          title="这里只修改排程输入（交期、急单标记），不会改写布车状态、缸位或开缸执行单。连续修改会自动合并，停止操作约 800ms 后触发一次重算。"
          style="margin-bottom:10px" />
        <el-table :data="context.carts || []" size="small" border max-height="560">
          <el-table-column prop="cartCode" label="布车" width="95" />
          <el-table-column label="色号" width="120">
            <template #default="{ row }">
              <span class="color-chip"><i :style="{ background: colorHex(row.colorCode) }"></i>{{ row.colorName }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="fabricType" label="布种" width="95" />
          <el-table-column label="米/折算kg" width="120">
            <template #default="{ row }">{{ row.meters }}m / {{ row.weightKg }}kg</template>
          </el-table-column>
          <el-table-column label="交期" width="210">
            <template #default="{ row }">
              <el-date-picker :model-value="row.dueTime" type="datetime" size="small"
                value-format="YYYY-MM-DDTHH:mm:ss" format="MM-dd HH:mm"
                @update:model-value="(v) => saveCartInput(row, { dueTime: v || '' })" />
            </template>
          </el-table-column>
          <el-table-column label="急单" width="80">
            <template #default="{ row }">
              <el-switch :model-value="!!row.urgent"
                         @update:model-value="(v) => saveCartInput(row, { urgent: v ? 1 : 0 })" />
            </template>
          </el-table-column>
          <el-table-column label="资料" min-width="150">
            <template #default="{ row }">
              <el-tag v-if="row.recipeOk && row.curveOk" type="success" size="small">资料齐备 {{ row.recipeCode }}</el-tag>
              <el-tag v-else type="danger" size="small">
                {{ !row.recipeOk ? '缺已发布配方' : '缺工艺曲线' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 维护与锁定 -->
      <el-tab-pane label="维护与锁定段" name="ops">
        <OpsPanels :maintenance="maintenance" :locks="locks" :vats="context.vats"
                   :pending-carts="context.carts" :fabric-machines="rule.fabricMachines"
                   @create-maint="createMaintenance" @delete-maint="deleteMaintenance"
                   @create-lock="createLock" @delete-lock="deleteLock" />
      </el-tab-pane>

      <!-- 方案 -->
      <el-tab-pane label="方案留存与差异" name="plans">
        <PlanManager :plans="plans" @save="saveSnapshot" @view="viewPlan"
                     @reference="markReference" @replay="replayPlan" @remove="removePlan" />
      </el-tab-pane>

      <!-- 规则 -->
      <el-tab-pane label="清洗规则版本" name="rules">
        <div class="rules-layout">
          <div class="rules-layout__list">
            <h4>版本列表</h4>
            <div v-for="r in ruleVersions" :key="r.id" class="rv"
                 :class="{ 'rv--active': r.status === 'ACTIVE' }">
              <b>{{ r.versionCode }}</b>
              <el-tag size="small" :type="r.status === 'ACTIVE' ? 'success' : 'info'">
                {{ r.status === 'ACTIVE' ? '当前生效' : '已归档' }}
              </el-tag>
              <span class="rv__time">{{ fmt(r.createTime) }}</span>
              <div class="rv__remark">{{ r.remark }}</div>
              <el-button link type="primary" size="small" @click="viewRuleVersion(r)">查看该版本口径</el-button>
            </div>
          </div>
          <div class="rules-layout__edit">
            <el-alert v-if="editorRule && editorRule.status !== 'ACTIVE'" type="warning" :closable="false"
              show-icon style="margin-bottom:10px"
              :title="`正在查看历史版本 ${editorRule.versionCode}（已归档）。在此基础上保存会生成新的生效版本，该历史版本保持不变，旧方案仍可按它复算。`" />
            <RuleMatrixEditor v-if="editorRule" :key="editorRule.id" :initial="editorRule"
                              :all-colors="allColors" :publishing="publishing"
                              @publish="publishRule" />
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 作业块解释抽屉 -->
    <el-drawer v-model="itemDrawer" size="420px" :title="itemDrawerTitle">
      <template v-if="selectedItem">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="类型">{{ kindName(selectedItem.kind) }}</el-descriptions-item>
          <el-descriptions-item label="染缸">{{ selectedItem.vatCode }}</el-descriptions-item>
          <el-descriptions-item v-if="selectedItem.cartCode" label="布车">{{ selectedItem.cartCode }}</el-descriptions-item>
          <el-descriptions-item label="时段">{{ fmt(selectedItem.start) }} ~ {{ fmt(selectedItem.end) }}（{{ selectedItem.minutes }} 分钟）</el-descriptions-item>
          <el-descriptions-item v-if="selectedItem.washCategoryLabel" label="清洗">
            {{ selectedItem.washCategoryLabel }}：{{ selectedItem.washMinutes }} 分钟 / {{ selectedItem.washCost }} 元
          </el-descriptions-item>
          <el-descriptions-item v-if="selectedItem.note" label="说明">{{ selectedItem.note }}</el-descriptions-item>
        </el-descriptions>
        <div v-if="selectedDecision" class="drawer-reasons">
          <h5>为什么这样安排</h5>
          <ul>
            <li v-for="(r, i) in selectedDecision.reasons" :key="i">{{ r }}</li>
          </ul>
        </div>
      </template>
    </el-drawer>

    <!-- 方案查看抽屉 -->
    <el-drawer v-model="planDrawer" size="86%" :title="planDrawerTitle">
      <ScheduleGantt v-if="viewingResult" :timeline="viewingResult.timeline" :vats="viewingContext.vats"
                     :loads="viewingResult.vatLoads" :horizon-start="viewingContext.horizonStart"
                     :horizon-end="viewingContext.horizonEnd" @select-item="showItem" />
      <DecisionPanel v-if="viewingResult" :result="viewingResult" style="margin-top:12px" />
    </el-drawer>

    <!-- 旧版本复算弹窗 -->
    <el-dialog v-model="replayDlg" :title="`旧版本复算：${replayData?.planCode || ''}`" width="86%" top="4vh">
      <el-alert v-if="replayData" :type="replayData.consistent ? 'success' : 'warning'" :closable="false"
        show-icon style="margin-bottom:10px"
        :title="replayData.consistent
          ? `按存档规则版本 ${replayData.ruleVersionCode}（${replayData.ruleVersionStatus === 'ACTIVE' ? '仍为当前生效' : '已归档'}）复算，结果与留存时一致，原解释可追溯。`
          : '按存档口径复算结果与留存时存在差异（输入快照与规则版本已一并留存，展示的是当时口径下的结果）。'" />
      <ScheduleGantt v-if="replayData" :timeline="replayData.result.timeline" :vats="replayVats"
                     :loads="replayData.result.vatLoads" :horizon-start="replayData.result.horizonStart"
                     :horizon-end="replayData.result.horizonEnd" />
      <DecisionPanel v-if="replayData" :result="replayData.result" style="margin-top:12px" />
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  scheduleState, scheduleRecompute, scheduleJobStatus, listSchedulePlans,
  getDraftSchedulePlan, getSchedulePlan,
  saveSchedulePlan, markReferencePlan, deleteSchedulePlan, replaySchedulePlan,
  listRuleVersions, apiPublishRuleVersion,
  apiListMaintenance, apiCreateMaintenance, apiDeleteMaintenance,
  apiListLocks, apiCreateLock, apiDeleteLock,
  updateCartScheduleInput, listRecipes
} from '../api'
import ScheduleGantt from './schedule/ScheduleGantt.vue'
import DecisionPanel from './schedule/DecisionPanel.vue'
import PlanManager from './schedule/PlanManager.vue'
import OpsPanels from './schedule/OpsPanels.vue'
import RuleMatrixEditor from './schedule/RuleMatrixEditor.vue'

const props = defineProps({ params: { type: Object, default: () => ({}) } })
const activeTab = ref(props.params.tab || 'timeline')

const context = ref({ carts: [], vats: [] })
const plans = ref([])
const draftPlan = ref(null)
const maintenance = ref([])
const locks = ref([])
const rule = ref({ matrix: {}, fabricMachines: [], params: {} })
const ruleVersions = ref([])
const editorRule = ref(null)
const job = ref(null)
let pollTimer = null

const result = computed(() => draftPlan.value ? safeParse(draftPlan.value.resultJson) : null)
const stats = computed(() => draftPlan.value ? safeParse(draftPlan.value.statsJson) : {})
const ruleVersionCode = computed(() => result.value?.ruleVersionCode || rule.value.versionCode || '-')
const jobRunning = computed(() => job.value && ['PENDING', 'RUNNING'].includes(job.value.status))
const jobTagType = computed(() => ({
  PENDING: 'info', RUNNING: 'warning', DONE: 'success', STALE: 'warning', FAILED: 'danger'
}[job.value?.status] || 'info'))
const jobText = computed(() => {
  if (!job.value) return ''
  const map = { PENDING: '已排队，等待计算', RUNNING: '正在按最新输入计算…', DONE: '结果已是最新', STALE: job.value.message || '已排队重算', FAILED: '重算失败' }
  return map[job.value.status] || job.value.status
})

const allColors = ref([])

async function loadState() {
  const { data } = await scheduleState()
  context.value = data.context
  rule.value = data.activeRule
  ruleVersions.value = await listRuleVersions().then((r) => r.data)
  editorRule.value = data.activeRule
  await refreshPlans()
  maintenance.value = await apiListMaintenance().then((r) => r.data)
  locks.value = await apiListLocks().then((r) => r.data)
  job.value = data.job.status === 'IDLE' ? null : data.job
  if (jobRunning.value) startPolling()
  if (!result.value) {
    // 首次进入没有草案时自动后台算一次
    recompute(true)
  }
}
async function refreshPlans() {
  plans.value = await listSchedulePlans().then((r) => r.data)
  const draft = await getDraftSchedulePlan().then((r) => r.data)
  draftPlan.value = draft
}

async function recompute(silent) {
  const { data } = await scheduleRecompute({})
  job.value = data
  startPolling()
  if (!silent) ElMessage.success('重算已提交，页面可继续操作，完成后自动刷新')
}
function startPolling() {
  if (pollTimer) return
  pollTimer = setInterval(pollJob, 700)
}
async function pollJob() {
  const { data } = await scheduleJobStatus()
  job.value = data.status === 'IDLE' ? null : data
  if (['DONE', 'FAILED', 'STALE'].includes(data.status)) {
    clearInterval(pollTimer)
    pollTimer = null
    if (data.status === 'DONE') {
      await refreshPlans()
    }
    if (data.status === 'STALE') {
      // 后端已自动排队下一轮，继续轮询
      startPolling()
    }
  }
}

/* ---- 连续编辑交期/急单：前端防抖合并 ---- */
let recomputeTimer = null
function scheduleDebouncedRecompute() {
  clearTimeout(recomputeTimer)
  recomputeTimer = setTimeout(() => recompute(true), 800)
}
async function saveCartInput(row, patch) {
  await updateCartScheduleInput(row.id, patch)
  Object.assign(row, patch.dueTime === '' ? { dueTime: null } : patch)
  scheduleDebouncedRecompute()
}

/* ---- 维护 ---- */
async function createMaintenance(payload) {
  try {
    await apiCreateMaintenance(payload)
    ElMessage.success('维护时段已登记，准备重算…')
    maintenance.value = await apiListMaintenance().then((r) => r.data)
    recompute(true)
  } catch (e) { ElMessage.error(e.message) }
}
async function deleteMaintenance(row) {
  try {
    await ElMessageBox.confirm(`删除 ${row.vatCode} 的维护时段？`, '确认', { type: 'warning' })
    await apiDeleteMaintenance(row.id)
    maintenance.value = await apiListMaintenance().then((r) => r.data)
    recompute(true)
  } catch (e) { if (e !== 'cancel') ElMessage.error(e.message) }
}

/* ---- 锁定 ---- */
async function createLock(payload) {
  try {
    await apiCreateLock(payload)
    ElMessage.success('已锁定承诺段，准备重算…')
    locks.value = await apiListLocks().then((r) => r.data)
    recompute(true)
  } catch (e) { ElMessage.error(e.message) }
}
async function deleteLock(row) {
  try {
    await ElMessageBox.confirm(`解除布车 ${row.cartCode} 在 ${row.vatCode} 的锁定段？解锁后下次重算将重新安排该车。`,
      '确认解锁', { type: 'warning' })
    await apiDeleteLock(row.id)
    locks.value = await apiListLocks().then((r) => r.data)
    recompute(true)
  } catch (e) { if (e !== 'cancel') ElMessage.error(e.message) }
}

/* ---- 方案 ---- */
async function saveSnapshot() {
  const { value } = await ElMessageBox.prompt('给留存方案起个名字', '留存方案', {
    confirmButtonText: '留存', cancelButtonText: '取消',
    inputValue: `方案 ${new Date().toLocaleString('zh-CN', { hour12: false })}`
  })
  await saveSchedulePlan({ name: value })
  ElMessage.success('方案已留存（不可变）')
  await refreshPlans()
  activeTab.value = 'plans'
}
async function markReference(row) {
  await markReferencePlan(row.id)
  ElMessage.success(`「${row.name}」已选为车间参考；布车与执行单未做任何改动`)
  await refreshPlans()
}
async function removePlan(row) {
  await ElMessageBox.confirm(`删除留存方案 ${row.planCode}？`, '确认', { type: 'warning' })
  await deleteSchedulePlan(row.id)
  await refreshPlans()
}

const planDrawer = ref(false)
const planDrawerTitle = ref('')
const viewingResult = ref(null)
const viewingContext = ref({ vats: [], horizonStart: null, horizonEnd: null })
async function viewPlan(row) {
  const full = await getSchedulePlan(row.id).then((r) => r.data)
  viewingResult.value = safeParse(full.resultJson)
  const snap = safeParse(full.inputSnapshotJson)
  viewingContext.value = {
    vats: snap.vats || [],
    horizonStart: snap.horizonStart,
    horizonEnd: snap.horizonEnd
  }
  planDrawerTitle.value = `${full.planCode} · ${full.name}（规则版本 ${full.ruleVersionCode}）`
  planDrawer.value = true
}

const replayDlg = ref(false)
const replayData = ref(null)
const replayVats = computed(() => replayData.value?.vats || [])
async function replayPlan(row) {
  const { data } = await replaySchedulePlan(row.id)
  replayData.value = data
  replayDlg.value = true
}

/* ---- 规则版本 ---- */
const publishing = ref(false)
async function publishRule(payload) {
  publishing.value = true
  try {
    await apiPublishRuleVersion({ ...payload, operator: '调度员' })
    ElMessage.success('新版本已生效，旧方案仍按各自版本复算')
    ruleVersions.value = await listRuleVersions().then((r) => r.data)
    const active = ruleVersions.value.find((r) => r.status === 'ACTIVE')
    if (active) editorRule.value = await hydrateRule(active)
    recompute(true)
  } finally {
    publishing.value = false
  }
}
async function viewRuleVersion(r) {
  editorRule.value = await hydrateRule(r)
}
async function hydrateRule(r) {
  // 列表接口只有实体（JSON 字符串），state 里的 ACTIVE 是对象；这里兼容两种
  if (r.matrix && r.params) return r
  return {
    id: r.id, versionCode: r.versionCode, status: r.status,
    matrix: safeParse(r.matrixJson), fabricMachines: safeParse(r.fabricMachineJson),
    params: safeParse(r.paramsJson), remark: r.remark, createTime: r.createTime, readonly: r.status !== 'ACTIVE'
  }
}

/* ---- 时间轴块抽屉 ---- */
const itemDrawer = ref(false)
const selectedItem = ref(null)
const selectedDecision = ref(null)
const itemDrawerTitle = computed(() => {
  const it = selectedItem.value
  if (!it) return ''
  return { PROCESS: '染色作业', WASH: '换色清洗段', MAINTENANCE: '设备维护', LOCK: '人工锁定段' }[it.kind] || '时间块'
})
function showItem(it) {
  selectedItem.value = it
  selectedDecision.value = (result.value?.decisions || []).find((d) => d.cartId === it.cartId) || null
  itemDrawer.value = true
}

/* ---- 工具 ---- */
function safeParse(json) {
  if (!json) return null
  try { return JSON.parse(json) } catch { return null }
}
function fmt(t) { return t ? String(t).replace('T', ' ').slice(0, 16) : '' }
function fmtDate(t) { return t ? String(t).replace('T', ' ').slice(0, 10) : '' }
function machineName(t) { return { OVERFLOW: '溢喷', AIRFLOW: '气流', JIG: '卷染' }[t] || t }
function kindName(k) { return { PROCESS: '染色作业', WASH: '换色清洗', MAINTENANCE: '设备维护', LOCK: '人工锁定' }[k] || k }
function formatHours(min) {
  if (min == null) return '0h'
  return Math.round(Number(min) / 60 * 10) / 10 + 'h'
}
function barPct(min) {
  const total = (new Date(context.value.horizonEnd) - new Date(context.value.horizonStart)) / 60000
  return Math.min(100, (Number(min || 0) / total) * 100) + '%'
}
function colorHex(code) {
  const c = allColors.value.find((x) => x.colorCode === code)
  return c?.colorHex || '#5b8def'
}

watch(() => props.params.tab, (tab) => { if (tab) activeTab.value = tab })

onMounted(async () => {
  allColors.value = await listRecipes().then((r) => r.data)
  await loadState()
})
</script>

<style scoped>
.board { padding: 12px 16px 20px; }
.board__toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.board__rule { display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--mill-text); }
.board__horizon { color: var(--mill-muted); font-size: 12px; }
.board__actions { display: flex; gap: 8px; align-items: center; }
.board__jobtag { max-width: 220px; }
.kpis { display: grid; grid-template-columns: repeat(8, 1fr); gap: 8px; margin-bottom: 12px; }
.kpi { background: #fff; border: 1px solid var(--mill-line); border-radius: 8px; padding: 8px 10px; display: flex; flex-direction: column; gap: 2px; }
.kpi span { font-size: 11px; color: var(--mill-muted); }
.kpi b { font-size: 20px; color: var(--mill-primary-deep); }
.kpi i { font-style: normal; font-size: 11px; color: var(--mill-muted); }
.kpi.ok b { color: #2e7d32; }
.kpi.warn b { color: #b26a00; }
.kpi.danger b { color: #c62828; }
.board__tabs { background: transparent; }
.board__row-title { font-size: 13px; font-weight: 700; color: var(--mill-primary-deep); margin: 14px 0 8px; }
.loads { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
.load-card { background: #fff; border: 1px solid var(--mill-line); border-radius: 8px; padding: 8px 10px; }
.load-card__head { display: flex; justify-content: space-between; font-size: 12px; margin-bottom: 6px; }
.load-card__head span { color: var(--mill-muted); }
.load-bar { position: relative; height: 12px; background: #f1ecf8; border-radius: 6px; overflow: hidden; }
.load-bar span { position: absolute; top: 0; bottom: 0; }
.load-bar__busy { background: #6a1fb0; left: 0; }
.load-bar__wash { background: #f0ad4e; }
.load-bar__maint { background: #9e9e9e; }
.load-card__nums { display: flex; gap: 10px; flex-wrap: wrap; font-size: 11px; color: var(--mill-muted); margin-top: 5px; }
.load-card__nums .wash { color: #b26a00; }
.load-card__nums .maint { color: #616161; }
.color-chip { display: inline-flex; align-items: center; gap: 5px; }
.color-chip i { width: 12px; height: 12px; border-radius: 3px; border: 1px solid rgba(0,0,0,.15); display: inline-block; }
.rules-layout { display: grid; grid-template-columns: 260px 1fr; gap: 12px; align-items: start; }
.rules-layout__list { background: #fff; border: 1px solid var(--mill-line); border-radius: 8px; padding: 10px; }
.rules-layout__list h4 { margin: 0 0 8px; font-size: 14px; color: var(--mill-primary-deep); }
.rv { border: 1px solid var(--mill-line); border-radius: 6px; padding: 8px; margin-bottom: 8px; display: flex; flex-direction: column; gap: 4px; }
.rv--active { border-color: var(--mill-primary); background: #f8f3fe; }
.rv__time { font-size: 11px; color: var(--mill-muted); }
.rv__remark { font-size: 12px; color: var(--mill-text); }
.drawer-reasons { margin-top: 14px; }
.drawer-reasons h5 { margin: 0 0 6px; color: var(--mill-primary-deep); }
.drawer-reasons ul { margin: 0; padding-left: 18px; }
.drawer-reasons li { font-size: 13px; line-height: 1.7; }
@media (max-width: 1500px) { .kpis { grid-template-columns: repeat(4, 1fr); } .loads { grid-template-columns: repeat(2, 1fr); } }
</style>
