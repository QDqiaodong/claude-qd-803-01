<template>
  <div class="ops">
    <div class="ops__col">
      <div class="ops__head">
        <h4>设备维护时段（排程硬约束）</h4>
        <el-button size="small" type="primary" plain @click="openMaint()">新增维护</el-button>
      </div>
      <el-table :data="maintenance" size="small" border>
        <el-table-column prop="vatCode" label="染缸" width="80" />
        <el-table-column label="开始" width="150">
          <template #default="{ row }">{{ fmt(row.startTime) }}</template>
        </el-table-column>
        <el-table-column label="结束" width="150">
          <template #default="{ row }">{{ fmt(row.endTime) }}</template>
        </el-table-column>
        <el-table-column prop="reason" label="原因" min-width="180" />
        <el-table-column label="操作" width="70">
          <template #default="{ row }">
            <el-button link type="danger" size="small" @click="$emit('delete-maint', row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="ops__col">
      <div class="ops__head">
        <h4>人工锁定段（重算不可移动）</h4>
        <el-button size="small" type="primary" plain @click="openLock()">锁定承诺段</el-button>
      </div>
      <el-table :data="locks" size="small" border>
        <el-table-column prop="cartCode" label="布车" width="90" />
        <el-table-column prop="vatCode" label="染缸" width="70" />
        <el-table-column label="承诺时段" width="240">
          <template #default="{ row }">{{ fmt(row.startTime) }} ~ {{ hm(row.endTime) }}</template>
        </el-table-column>
        <el-table-column prop="note" label="承诺说明" min-width="180" />
        <el-table-column label="操作" width="70">
          <template #default="{ row }">
            <el-button link type="danger" size="small" @click="$emit('delete-lock', row)">解锁</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 新增维护弹窗 -->
    <el-dialog v-model="maintDlg" title="新增设备维护时段" width="460px">
      <el-form label-width="72px">
        <el-form-item label="染缸">
          <el-select v-model="maintForm.vatId" placeholder="选择染缸" style="width: 100%">
            <el-option v-for="v in vats" :key="v.id"
                       :label="`${v.vatCode}（${machineName(v.machineType)} ${v.capacityKg}kg）`" :value="v.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="时段">
          <el-date-picker v-model="maintRange" type="datetimerange" range-separator="至"
            start-placeholder="开始" end-placeholder="结束" value-format="YYYY-MM-DD HH:mm:ss"
            format="MM-dd HH:mm" style="width: 100%" />
        </el-form-item>
        <el-form-item label="原因">
          <el-input v-model="maintForm.reason" placeholder="如 辊筒更换 / 仪表校准" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="maintDlg = false">取消</el-button>
        <el-button type="primary" @click="submitMaint">确定</el-button>
      </template>
    </el-dialog>

    <!-- 新增锁定弹窗 -->
    <el-dialog v-model="lockDlg" title="锁定已承诺的染缸与时段" width="480px">
      <el-form label-width="72px">
        <el-form-item label="布车">
          <el-select v-model="lockForm.cartId" filterable placeholder="选择待排产布车" style="width: 100%"
                     @change="onLockCart">
            <el-option v-for="c in pendingCarts" :key="c.id"
                       :label="`${c.cartCode} ${c.colorName} ${c.fabricType} ${c.weightKg}kg${c.urgent ? '（急）' : ''}`"
                       :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="染缸">
          <el-select v-model="lockForm.vatId" placeholder="系统会校验机型/容量/维护冲突" style="width: 100%">
            <el-option v-for="v in compatibleVats" :key="v.id"
                       :label="`${v.vatCode}（${machineName(v.machineType)} ${v.capacityKg}kg）`" :value="v.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="时段">
          <el-date-picker v-model="lockRange" type="datetimerange" range-separator="至"
            start-placeholder="开始" end-placeholder="结束" value-format="YYYY-MM-DD HH:mm:ss"
            format="MM-dd HH:mm" style="width: 100%" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="lockForm.note" placeholder="承诺客户/已沟通说明" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="lockDlg = false">取消</el-button>
        <el-button type="primary" @click="submitLock">锁定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'

const props = defineProps({
  maintenance: { type: Array, default: () => [] },
  locks: { type: Array, default: () => [] },
  vats: { type: Array, default: () => [] },
  pendingCarts: { type: Array, default: () => [] },
  fabricMachines: { type: Array, default: () => [] }
})
const emit = defineEmits(['create-maint', 'create-lock'])

const maintDlg = ref(false)
const maintRange = ref([])
const maintForm = reactive({ vatId: null, reason: '' })
function openMaint() {
  maintForm.vatId = null
  maintForm.reason = ''
  maintRange.value = []
  maintDlg.value = true
}
function submitMaint() {
  emit('create-maint', {
    vatId: maintForm.vatId,
    startTime: maintRange.value?.[0],
    endTime: maintRange.value?.[1],
    reason: maintForm.reason
  })
  maintDlg.value = false
}

const lockDlg = ref(false)
const lockRange = ref([])
const lockForm = reactive({ cartId: null, vatId: null, note: '' })
function openLock() {
  lockForm.cartId = null
  lockForm.vatId = null
  lockForm.note = ''
  lockRange.value = []
  lockDlg.value = true
}
function onLockCart() {
  lockForm.vatId = null
}
const compatibleVats = computed(() => {
  const cart = props.pendingCarts.find((c) => c.id === lockForm.cartId)
  if (!cart) return props.vats
  const allow = props.fabricMachines.find((f) => f.fabricType === cart.fabricType)?.machineTypes || []
  return props.vats.filter((v) => allow.includes(v.machineType)
    && (!v.capacityKg || !cart.weightKg || Number(v.capacityKg) >= Number(cart.weightKg)))
})
function submitLock() {
  emit('create-lock', {
    cartId: lockForm.cartId,
    vatId: lockForm.vatId,
    startTime: lockRange.value?.[0],
    endTime: lockRange.value?.[1],
    note: lockForm.note
  })
  lockDlg.value = false
}

function fmt(t) { return t ? String(t).replace('T', ' ').slice(0, 16) : '' }
function hm(t) { return t ? String(t).slice(11, 16) : '' }
function machineName(t) { return { OVERFLOW: '溢喷', AIRFLOW: '气流', JIG: '卷染' }[t] || t }
</script>

<style scoped>
.ops { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
@media (max-width: 1280px) { .ops { grid-template-columns: 1fr; } }
.ops__col { background: #fff; border: 1px solid var(--mill-line); border-radius: 8px; padding: 10px 12px; }
.ops__head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.ops__head h4 { margin: 0; font-size: 14px; color: var(--mill-primary-deep); }
</style>
