<template>
  <div class="gantt">
    <div class="gantt__legend">
      <span class="lg"><i class="sw" :style="{ background: '#5b8def' }"></i>染色作业</span>
      <span class="lg"><i class="sw" :style="{ background: 'repeating-linear-gradient(45deg,#f0ad4e,#f0ad4e 4px,#f7d9b3 4px,#f7d9b3 8px)' }"></i>换色清洗</span>
      <span class="lg"><i class="sw" :style="{ background: 'repeating-linear-gradient(45deg,#9e9e9e,#9e9e9e 4px,#d8d8d8 4px,#d8d8d8 8px)' }"></i>设备维护</span>
      <span class="lg"><i class="sw lock-sw"></i>人工锁定段</span>
      <span class="lg"><i class="sw urgent-sw"></i>急单</span>
      <span class="gantt__hint">点击作业块查看「为什么排在这 / 为何延后或排除」</span>
    </div>

    <div class="gantt__scroll">
      <div class="gantt__inner" :style="{ width: rightWidth + 'px' }">
        <!-- 日期刻度 -->
        <div class="gantt__axis">
          <div class="gantt__axis-label">缸号 / 机型</div>
          <div class="gantt__axis-track" :style="{ width: rightWidth + 'px' }">
            <div v-for="d in dayTicks" :key="d.label" class="gantt__day"
                 :style="{ left: pct(d.at) + '%', width: pctWidth(d.minutes) + '%' }">
              <span>{{ d.label }}</span>
            </div>
          </div>
        </div>

        <div v-for="(row, idx) in rows" :key="row.vatId" class="gantt__row"
             :class="{ 'gantt__row--alt': idx % 2 === 1 }">
          <div class="gantt__row-label">
            <b>{{ row.vatCode }}</b>
            <span>{{ machineName(row.machineType) }} · {{ row.capacityKg }}kg</span>
            <em v-if="loadOf(row.vatId) !== null" :class="loadClass(row.vatId)">
              负载 {{ loadOf(row.vatId) }}%
            </em>
          </div>
          <div class="gantt__track" :style="{ width: rightWidth + 'px' }">
            <div v-for="d in dayTicks" :key="'g' + d.label + row.vatId" class="gantt__grid"
                 :style="{ left: pct(d.at) + '%', width: pctWidth(d.minutes) + '%' }"></div>
            <div v-for="(it, i) in row.items" :key="i" class="gantt__blk"
                 :class="blockClass(it)"
                 :style="blockStyle(it)"
                 :title="tip(it)"
                 @click="$emit('select-item', it)">
              <template v-if="it.kind === 'PROCESS' || it.kind === 'LOCK'">
                <span class="gantt__blk-code">{{ it.cartCode || it.colorName }}</span>
                <span class="gantt__blk-time">{{ hm(it.start) }}-{{ hm(it.end) }}</span>
              </template>
              <template v-else-if="it.kind === 'WASH'">
                <span>{{ it.washCategoryLabel }} {{ it.minutes }}′</span>
              </template>
              <template v-else-if="it.kind === 'MAINTENANCE'">
                <span>维护 {{ Math.round(it.minutes / 60) }}h</span>
              </template>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  timeline: { type: Array, default: () => [] },
  vats: { type: Array, default: () => [] },
  loads: { type: Array, default: () => [] },
  horizonStart: { type: String, required: true },
  horizonEnd: { type: String, required: true }
})
defineEmits(['select-item'])

const pxPerDay = 300
const dayMs = 24 * 3600 * 1000
const rightWidth = computed(() => Math.max(600, days() * pxPerDay))

function days() {
  return (new Date(props.horizonEnd) - new Date(props.horizonStart)) / dayMs
}
function dayTicks() {
  const start = new Date(props.horizonStart)
  const end = new Date(props.horizonEnd)
  const ticks = []
  const cursor = new Date(start)
  while (cursor < end) {
    const next = new Date(cursor.getTime() + dayMs)
    const stop = next > end ? end : next
    const label = `${cursor.getMonth() + 1}/${cursor.getDate()}`
    ticks.push({
      label,
      at: cursor.toISOString(),
      minutes: (stop - cursor) / 60000
    })
    cursor.setTime(next.getTime())
  }
  return ticks
}

function totalMinutes() {
  return (new Date(props.horizonEnd) - new Date(props.horizonStart)) / 60000
}
function offsetMin(t) {
  return Math.max(0, (new Date(t) - new Date(props.horizonStart)) / 60000)
}
function pct(t) {
  return offsetMin(t) / totalMinutes() * 100
}
function pctWidth(min) {
  return min / totalMinutes() * 100
}
function hm(t) {
  const d = new Date(t)
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

const rows = computed(() => props.vats.map((v) => ({
  ...v,
  items: props.timeline
    .filter((it) => it.vatId === v.id && it.kind !== 'IDLE')
    .sort((a, b) => new Date(a.start) - new Date(b.start))
})))

function blockStyle(it) {
  const left = pct(it.start)
  const width = Math.max(pctWidth(it.minutes), it.kind === 'WASH' ? 1.2 : 1.8)
  const style = { left: left + '%', width: width + '%' }
  if (it.kind === 'PROCESS' || it.kind === 'LOCK') {
    style.background = it.colorHex || '#5b8def'
  }
  return style
}
function blockClass(it) {
  return {
    'gantt__blk--process': it.kind === 'PROCESS',
    'gantt__blk--wash': it.kind === 'WASH',
    'gantt__blk--maint': it.kind === 'MAINTENANCE',
    'gantt__blk--lock': it.kind === 'LOCK',
    'gantt__blk--urgent': it.urgent
  }
}
function tip(it) {
  if (it.kind === 'WASH') {
    return `${it.washCategoryLabel}：${it.fromColor} → ${it.toColor}，${it.washMinutes} 分钟，约 ${it.washCost} 元`
  }
  if (it.kind === 'MAINTENANCE') return `设备维护：${it.note || ''}（${it.minutes} 分钟）`
  if (it.kind === 'LOCK') return `人工锁定：${it.cartCode} ${it.note || ''}`
  return `${it.cartCode} ${it.colorName || ''} ${hm(it.start)}-${hm(it.end)}${it.urgent ? '（急单）' : ''}`
}
function machineName(t) {
  return { OVERFLOW: '溢喷', AIRFLOW: '气流', JIG: '卷染' }[t] || t
}
function loadOf(vatId) {
  const l = props.loads.find((x) => x.vatId === vatId)
  return l ? Math.round(l.loadRate) : null
}
function loadClass(vatId) {
  const v = loadOf(vatId)
  if (v === null) return ''
  return v >= 85 ? 'ld-hot' : v >= 50 ? 'ld-mid' : 'ld-low'
}
</script>

<style scoped>
.gantt { border: 1px solid var(--mill-line); border-radius: 8px; background: #fff; overflow: hidden; }
.gantt__legend {
  display: flex; align-items: center; gap: 16px; flex-wrap: wrap;
  padding: 8px 12px; border-bottom: 1px solid var(--mill-line);
  font-size: 12px; color: var(--mill-muted); background: #faf8fd;
}
.gantt__legend .lg { display: inline-flex; align-items: center; gap: 5px; }
.gantt__legend .sw { width: 16px; height: 11px; border-radius: 2px; display: inline-block; border: 1px solid rgba(0,0,0,.12); }
.lock-sw { background: #7e57c2; border: 1px dashed #311b92 !important; }
.urgent-sw { background: #fff; border: 2px solid #c62828 !important; }
.gantt__hint { margin-left: auto; }
.gantt__scroll { overflow-x: auto; }
.gantt__inner { min-width: 100%; }
.gantt__axis { display: flex; position: sticky; top: 0; z-index: 5; }
.gantt__axis-label {
  flex: 0 0 128px; width: 128px; padding: 6px 8px; font-size: 12px; color: var(--mill-muted);
  background: #f3ebfa; border-bottom: 1px solid var(--mill-line);
}
.gantt__axis-track { position: relative; height: 26px; background: #f3ebfa; border-bottom: 1px solid var(--mill-line); }
.gantt__day {
  position: absolute; top: 0; bottom: 0; border-left: 1px solid var(--mill-line);
  font-size: 12px; color: var(--mill-text); padding: 4px 6px; font-weight: 600;
}
.gantt__row { display: flex; }
.gantt__row--alt .gantt__track { background: #fcfaff; }
.gantt__row-label {
  flex: 0 0 128px; width: 128px; padding: 4px 8px; font-size: 12px;
  border-bottom: 1px solid #f0eaf7; display: flex; flex-direction: column; line-height: 1.35;
}
.gantt__row-label b { color: var(--mill-primary-deep); }
.gantt__row-label span { color: var(--mill-muted); }
.gantt__row-label em { font-style: normal; font-size: 11px; align-self: flex-start; border-radius: 3px; padding: 0 4px; }
.ld-hot { color: #b71c1c; background: #fde7e7; }
.ld-mid { color: #8a5a00; background: #fdf3df; }
.ld-low { color: #2e7d32; background: #e8f5e9; }
.gantt__track { position: relative; height: 40px; border-bottom: 1px solid #f0eaf7; }
.gantt__grid { position: absolute; top: 0; bottom: 0; border-left: 1px dashed #ece5f6; }
.gantt__blk {
  position: absolute; top: 5px; bottom: 5px; border-radius: 4px; color: #fff;
  font-size: 10.5px; line-height: 1.2; padding: 2px 4px; overflow: hidden;
  display: flex; flex-direction: column; justify-content: center; cursor: pointer;
  box-shadow: inset 0 -2px 0 rgba(0,0,0,.18); transition: transform .1s, box-shadow .1s;
}
.gantt__blk:hover { transform: translateY(-1px); box-shadow: 0 3px 8px rgba(74,20,140,.28); z-index: 4; }
.gantt__blk-code { font-weight: 700; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.gantt__blk-time { opacity: .9; white-space: nowrap; }
.gantt__blk--wash {
  color: #6b3e00; background: repeating-linear-gradient(45deg,#f0ad4e,#f0ad4e 4px,#f8dbb1 4px,#f8dbb1 8px);
  align-items: center; box-shadow: none;
}
.gantt__blk--maint {
  color: #4a4a4a; background: repeating-linear-gradient(45deg,#9e9e9e,#9e9e9e 4px,#dcdcdc 4px,#dcdcdc 8px);
  align-items: center; box-shadow: none;
}
.gantt__blk--lock { box-shadow: inset 0 0 0 2px rgba(49,27,146,.85), inset 0 -2px 0 rgba(0,0,0,.18); }
.gantt__blk--urgent { box-shadow: inset 0 0 0 2px #c62828, inset 0 -2px 0 rgba(0,0,0,.18); }
.gantt__blk--lock.gantt__blk--urgent { box-shadow: inset 0 0 0 2px #c62828, inset 0 0 0 4px rgba(49,27,146,.55); }
</style>
