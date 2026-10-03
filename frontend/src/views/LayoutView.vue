<template>
  <section class="panel">
    <div class="panel-head">
      <h3>切割排版图</h3>
      <span class="panel-sub">纯 SVG 手绘：一块荒料的排板面上按 x/y 坐标铺开已切出的大板，按质量等级着色，未排区域留虚线</span>
    </div>

    <div class="layout-top">
      <span class="panel-sub">荒料</span>
      <el-select v-model="blockId" filterable placeholder="选择荒料" style="width: 300px" @change="loadPlan">
        <el-option v-for="b in blocks" :key="b.id" :label="b.blockCode + ' · ' + b.stoneTypeName" :value="b.id">
          <span>{{ b.blockCode }} · {{ b.stoneTypeName }}</span>
          <span style="float: right; font-size: 12px; opacity: 0.7">
            {{ b.lengthCm }}×{{ b.widthCm }}×{{ b.heightCm }}cm
          </span>
        </el-option>
      </el-select>

      <el-button type="primary" :loading="relaying" @click="doRelayout">重新排板</el-button>

      <div class="waste-figure">
        <b>{{ plan.wasteRate || 0 }}</b><span>% 损耗率</span>
      </div>
      <div class="waste-figure">
        <b>{{ plan.placedCount || 0 }}/{{ plan.slabCount || 0 }}</b><span>已排 / 大板数</span>
      </div>
      <div class="waste-figure">
        <b>{{ plan.remainArea || 0 }}</b><span>m² 剩余留白</span>
      </div>
    </div>

    <div ref="wrapRef" class="svg-wrap">
      <svg v-if="plan.lengthCm" :viewBox="viewBox" preserveAspectRatio="xMidYMid meet" style="max-height: 520px">
        <defs>
          <pattern id="stoneHatch" width="14" height="14" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
            <rect width="14" height="14" :fill="stoneFill" />
            <line x1="0" y1="0" x2="0" y2="14" stroke="#b0bec5" stroke-width="3" opacity="0.55" />
          </pattern>
        </defs>

        <!-- 荒料大矩形：外框粗线 + 石材纹理斜纹 -->
        <rect x="0" y="0" :width="plan.lengthCm" :height="plan.widthCm"
              fill="url(#stoneHatch)" stroke="#2f4149" stroke-width="4" />
        <text x="6" y="-6" font-size="13" fill="#455a64">
          {{ plan.blockCode }} · {{ plan.stoneTypeName }} · {{ plan.lengthCm }}×{{ plan.widthCm }}cm（排板面 {{ plan.faceArea }}m²）
        </text>

        <!-- 未排区域：虚线留白 -->
        <rect x="0" y="0" :width="plan.lengthCm" :height="plan.widthCm"
              fill="none" stroke="#78909c" stroke-width="2" stroke-dasharray="10 8" />

        <!-- 已排板材：按质量等级着色，悬停看编号与尺寸，点击看详情 -->
        <g v-for="item in plan.items" :key="item.id">
          <rect class="slab-rect" :x="item.xPos" :y="item.yPos" :width="item.width" :height="item.height"
                :fill="item.color" fill-opacity="0.82" stroke="#ffffff" stroke-width="2"
                @mousemove="onHover($event, item)" @mouseleave="onLeave" @click="openSlab(item)">
            <title>{{ item.slabCode }} · {{ item.width }}×{{ item.height }}cm</title>
          </rect>
          <text v-if="item.width >= 55 && item.height >= 26"
                :x="item.xPos + 6" :y="item.yPos + 16" font-size="12" fill="#ffffff">
            #{{ item.seqNo }} {{ item.slabCode }}
          </text>
          <text v-if="item.width >= 55 && item.height >= 42"
                :x="item.xPos + 6" :y="item.yPos + 31" font-size="11" fill="#ffffff" opacity="0.9">
            {{ item.width }}×{{ item.height }} · {{ item.gradeName }}
          </text>
        </g>
      </svg>

      <div v-else class="empty-tip">请选择一块荒料</div>

      <!-- 自绘浮层：悬停板材显示编号与尺寸 -->
      <div v-show="tip.show" class="svg-tip" :style="{ left: tip.x + 'px', top: tip.y + 'px' }">
        <b>{{ tip.title }}</b>
        <div v-for="(line, i) in tip.lines" :key="i">{{ line }}</div>
      </div>
    </div>

    <div class="legend">
      <span><i style="background: #43a047"></i>A 级</span>
      <span><i style="background: #f9a825"></i>B 级</span>
      <span><i style="background: #90a4ae"></i>C 级</span>
      <span><i style="background: #e53935"></i>次品</span>
      <span style="color: #78909c">虚线框内为未排留白（剩余 {{ plan.remainArea || 0 }} m²）</span>
      <span style="margin-left: auto">点击板材可查看大板详情</span>
    </div>
  </section>

  <el-drawer v-model="slabVisible" title="大板详情" size="360px">
    <div v-if="slab.id">
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="大板编号">{{ slab.slabCode }}</el-descriptions-item>
        <el-descriptions-item label="所属荒料">{{ slab.blockCode }}</el-descriptions-item>
        <el-descriptions-item label="石种">{{ slab.stoneTypeName }}</el-descriptions-item>
        <el-descriptions-item label="尺寸">{{ slab.lengthCm }} × {{ slab.widthCm }} × {{ slab.thicknessCm }} cm</el-descriptions-item>
        <el-descriptions-item label="面积">{{ slab.areaM2 }} m²</el-descriptions-item>
        <el-descriptions-item label="表面处理">{{ slab.finishName }}</el-descriptions-item>
        <el-descriptions-item label="质量等级">
          <span class="grade-tag" :style="{ background: colorOf(slab.grade) }">{{ slab.gradeName }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="库位">{{ slab.location }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ slab.statusName }}</el-descriptions-item>
        <el-descriptions-item label="备注">{{ slab.remark || '—' }}</el-descriptions-item>
      </el-descriptions>
    </div>
  </el-drawer>
</template>

<script setup>
import { computed, inject, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { blockApi, layoutApi, slabApi } from '../api'

const mill = inject('mill')

const blocks = ref([])
const blockId = ref(null)
const plan = ref({})
const relaying = ref(false)
const wrapRef = ref(null)
const slabVisible = ref(false)
const slab = ref({})
const tip = reactive({ show: false, x: 0, y: 0, title: '', lines: [] })

const viewBox = computed(() => {
  const l = Number(plan.value.lengthCm || 0)
  const w = Number(plan.value.widthCm || 0)
  return `${-14} ${-22} ${l + 28} ${w + 40}`
})

const stoneFill = computed(() => {
  return { MARBLE: '#eceff1', GRANITE: '#e3e8ea', JADE: '#e0f0e6', QUARTZ: '#e8eaee', TRAVERTINE: '#f0ebe3' }[
    plan.value.stoneType
  ] || '#eceff1'
})

function colorOf(grade) {
  return { A: '#43a047', B: '#f9a825', C: '#90a4ae', DEFECT: '#e53935' }[grade] || '#90a4ae'
}

function onHover(event, item) {
  const box = wrapRef.value.getBoundingClientRect()
  tip.x = event.clientX - box.left + 14
  tip.y = event.clientY - box.top + 14
  tip.title = item.slabCode + ' · ' + item.gradeName
  tip.lines = [
    `${item.width} × ${item.height} cm（${item.areaM2} m²）`,
    `${item.finishName} · ${item.statusName}`,
    `锯切顺序 #${item.seqNo} · x=${item.xPos} y=${item.yPos}`
  ]
  tip.show = true
}

function onLeave() {
  tip.show = false
}

async function openSlab(item) {
  slab.value = await slabApi.detail(item.slabId)
  slabVisible.value = true
}

async function loadPlan() {
  if (!blockId.value) return
  plan.value = await layoutApi.plan(blockId.value)
}

async function doRelayout() {
  if (!blockId.value) return
  relaying.value = true
  try {
    plan.value = await layoutApi.relayout(blockId.value)
    ElMessage.success('已按剩余荒料重算排布')
    mill.refreshOverview()
  } finally {
    relaying.value = false
  }
}

// 左侧树点了荒料 → 首页跟着切
watch(
  () => mill.selectedBlockId.value,
  (id) => {
    if (id && id !== blockId.value) {
      blockId.value = id
      loadPlan()
    }
  }
)

onMounted(async () => {
  blocks.value = await blockApi.list()
  if (!blockId.value) {
    blockId.value = mill.selectedBlockId.value || (blocks.value[0] ? blocks.value[0].id : null)
  }
  await loadPlan()
})
</script>
