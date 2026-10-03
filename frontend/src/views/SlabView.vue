<template>
  <section class="panel">
    <div class="panel-head">
      <h3>大板库存</h3>
      <span class="panel-sub">卡片墙：每张卡片一个按真实长宽比缩小的矩形缩略图 + 面积，顶部为面积汇总</span>
      <div class="panel-extra">
        <el-select v-model="finish" clearable placeholder="表面处理" style="width: 130px" @change="load">
          <el-option label="光面" value="POLISHED" />
          <el-option label="亚光" value="HONED" />
          <el-option label="荔枝面" value="LITCHI" />
          <el-option label="火烧面" value="FLAMED" />
          <el-option label="皮革面" value="LEATHER" />
        </el-select>
        <el-input v-model="keyword" clearable placeholder="编号 / 库位" style="width: 170px"
                  @keyup.enter="load" @clear="load" />
        <el-input-number v-model="minArea" :min="0" :step="0.1" :precision="1" controls-position="right"
                         placeholder="面积≥" style="width: 120px" @change="load" />
        <el-button @click="load">刷新</el-button>
        <el-button type="primary" @click="openCreate">登记大板</el-button>
      </div>
    </div>

    <div class="summary-bar">
      <div class="summary-cell"><span>大板总数</span><b>{{ summary.slabCount || 0 }}</b> 片</div>
      <div class="summary-cell"><span>总面积</span><b>{{ summary.totalArea || 0 }}</b> m²</div>
      <div class="summary-cell"><span>在库面积</span><b>{{ summary.instockArea || 0 }}</b> m²</div>
      <div class="summary-cell"><span>在库 / 已预定</span><b>{{ summary.instockCount || 0 }} / {{ summary.reservedCount || 0 }}</b></div>
      <div class="summary-cell"><span>已出库 / 报废</span><b>{{ summary.outboundCount || 0 }} / {{ summary.scrapCount || 0 }}</b></div>
      <div class="summary-cell"><span>A / B / C / 次品</span>
        <b>{{ summary.gradeA || 0 }} / {{ summary.gradeB || 0 }} / {{ summary.gradeC || 0 }} / {{ summary.gradeDefect || 0 }}</b>
      </div>
    </div>

    <div class="slab-grid">
      <div v-for="s in rows" :key="s.id" class="slab-card" @click="openDetail(s)">
        <div class="thumb">
          <i :style="thumbStyle(s)" :title="s.lengthCm + '×' + s.widthCm + 'cm'"></i>
        </div>
        <div class="code">
          {{ s.slabCode }}
          <span class="grade-tag" :style="{ background: colorOf(s.grade) }">{{ s.gradeName }}</span>
        </div>
        <div v-if="s.qualityHold" class="hold-banner">
          ⛔ {{ s.qualityHoldStatusName }} · {{ s.qualityDefectName }} · 发货冻结
        </div>
        <div class="meta">
          {{ s.lengthCm }}×{{ s.widthCm }}×{{ s.thicknessCm }}cm · {{ s.finishName }}<br />
          {{ s.blockCode }} · {{ s.stoneTypeName }} · {{ s.location }}
        </div>
        <div class="area">{{ s.areaM2 }} m²
          <el-tag size="small" effect="plain" style="float: right">{{ s.statusName }}</el-tag>
        </div>
      </div>
    </div>

    <div v-if="!rows.length" class="empty-tip">当前筛选条件下没有大板</div>

    <div class="pager">
      <el-pagination background layout="total, sizes, prev, pager, next"
                     :total="total" :current-page="page" :page-size="size"
                     :page-sizes="[12, 24, 48]" @current-change="onPage" @size-change="onSize" />
    </div>
  </section>

  <el-drawer v-model="detailVisible" title="大板详情" size="360px">
    <div v-if="actRow.id">
      <div class="thumb" style="height: 110px">
        <i :style="thumbStyle(actRow, 150)"></i>
      </div>
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="大板编号">{{ actRow.slabCode }}</el-descriptions-item>
        <el-descriptions-item label="所属荒料">{{ actRow.blockCode }}</el-descriptions-item>
        <el-descriptions-item label="石种">{{ actRow.stoneTypeName }}</el-descriptions-item>
        <el-descriptions-item label="尺寸">{{ actRow.lengthCm }} × {{ actRow.widthCm }} × {{ actRow.thicknessCm }} cm</el-descriptions-item>
        <el-descriptions-item label="面积">{{ actRow.areaM2 }} m²</el-descriptions-item>
        <el-descriptions-item label="表面处理">{{ actRow.finishName }}</el-descriptions-item>
        <el-descriptions-item label="质量等级">
          <span class="grade-tag" :style="{ background: colorOf(actRow.grade) }">{{ actRow.gradeName }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="库位">{{ actRow.location }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ actRow.statusName }}</el-descriptions-item>
        <el-descriptions-item v-if="actRow.qualityHold" label="质量隔离">
          <el-tag size="small" type="danger">{{ actRow.qualityHoldStatusName }} · {{ actRow.qualityDefectName }}</el-tag>
          <div class="panel-sub">{{ actRow.qualityHoldNo }} · 隔离/待复检期间不能预定或出库</div>
        </el-descriptions-item>
        <el-descriptions-item label="备注">{{ actRow.remark || '—' }}</el-descriptions-item>
      </el-descriptions>
      <div style="margin-top: 14px; display: flex; gap: 8px">
        <el-button type="warning" @click="gotoQuality">质量处置</el-button>
        <el-button type="primary" @click="openStatus(actRow)">状态变更</el-button>
      </div>
    </div>
  </el-drawer>

  <el-dialog v-model="createVisible" title="登记大板" width="520px">
    <el-form :model="form" label-width="100px">
      <el-form-item label="大板编号"><el-input v-model="form.slabCode" placeholder="如 SLB-0035" /></el-form-item>
      <el-form-item label="所属荒料">
        <el-select v-model="form.blockId" filterable style="width: 100%">
          <el-option v-for="b in blocks" :key="b.id" :label="b.blockCode + ' · ' + b.stoneTypeName" :value="b.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="长/宽/厚(cm)">
        <el-input-number v-model="form.lengthCm" :min="1" :step="5" />
        <el-input-number v-model="form.widthCm" :min="1" :step="5" style="margin-left: 8px" />
        <el-input-number v-model="form.thicknessCm" :min="0.5" :step="0.5" :precision="1" style="margin-left: 8px" />
      </el-form-item>
      <el-form-item label="表面处理">
        <el-select v-model="form.finish" style="width: 100%">
          <el-option label="光面" value="POLISHED" />
          <el-option label="亚光" value="HONED" />
          <el-option label="荔枝面" value="LITCHI" />
          <el-option label="火烧面" value="FLAMED" />
          <el-option label="皮革面" value="LEATHER" />
        </el-select>
      </el-form-item>
      <el-form-item label="质量等级">
        <el-select v-model="form.grade" style="width: 100%">
          <el-option label="A 级" value="A" />
          <el-option label="B 级" value="B" />
          <el-option label="C 级" value="C" />
          <el-option label="次品" value="DEFECT" />
        </el-select>
      </el-form-item>
      <el-form-item label="库位"><el-input v-model="form.location" placeholder="如 A-03-01" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="createVisible = false">取消</el-button>
      <el-button type="primary" @click="doCreate">登记</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="statusVisible" title="大板状态变更" width="400px">
    <el-form label-width="90px">
      <el-form-item label="大板"><b>{{ actRow.slabCode }}</b></el-form-item>
      <el-form-item label="目标状态">
        <el-select v-model="nextStatus" style="width: 100%">
          <el-option label="在库" value="INSTOCK" />
          <el-option label="已预定" value="RESERVED" />
          <el-option label="已出库" value="OUTBOUND" />
          <el-option label="报废" value="SCRAP" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="statusVisible = false">取消</el-button>
      <el-button type="primary" @click="doStatus">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { inject, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { blockApi, slabApi } from '../api'

const mill = inject('mill')
const nav = useRouter()

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(24)
const summary = ref({})
const blocks = ref([])
const finish = ref('')
const keyword = ref('')
const minArea = ref(null)

const detailVisible = ref(false)
const createVisible = ref(false)
const statusVisible = ref(false)
const actRow = ref({})
const nextStatus = ref('INSTOCK')
const form = ref({
  slabCode: '', blockId: null, lengthCm: 160, widthCm: 100, thicknessCm: 2.0,
  finish: 'POLISHED', grade: 'A', location: ''
})

function colorOf(grade) {
  return { A: '#43a047', B: '#f9a825', C: '#90a4ae', DEFECT: '#e53935' }[grade] || '#90a4ae'
}

/** 缩略图：按真实长宽比缩到给定宽度内 */
function thumbStyle(s, maxWidth) {
  const limit = maxWidth || 120
  const l = Number(s.lengthCm || 1)
  const w = Number(s.widthCm || 1)
  const scale = Math.min(limit / l, 46 / w)
  return {
    width: Math.max(10, Math.round(l * scale)) + 'px',
    height: Math.max(8, Math.round(w * scale)) + 'px',
    background: colorOf(s.grade),
    opacity: 0.85
  }
}

function buildParams() {
  const params = { page: page.value, size: size.value, orderBy: 'area', desc: true }
  if (mill.filters.stoneType) params.stoneType = mill.filters.stoneType
  if (mill.filters.grade) params.grade = mill.filters.grade
  if (mill.filters.status) params.status = mill.filters.status
  if (finish.value) params.finish = finish.value
  if (keyword.value) params.keyword = keyword.value
  if (minArea.value) params.minArea = minArea.value
  return params
}

async function load() {
  const params = buildParams()
  const result = await slabApi.page(params)
  rows.value = result.rows
  total.value = result.total
  summary.value = await slabApi.summary(params)
}

function onPage(p) {
  page.value = p
  load()
}

function onSize(s) {
  size.value = s
  page.value = 1
  load()
}

function openCreate() {
  form.value = {
    slabCode: '', blockId: blocks.value[0] ? blocks.value[0].id : null,
    lengthCm: 160, widthCm: 100, thicknessCm: 2.0, finish: 'POLISHED', grade: 'A', location: ''
  }
  createVisible.value = true
}

async function doCreate() {
  await slabApi.create(form.value)
  createVisible.value = false
  ElMessage.success('大板已登记，面积按长×宽算出')
  load()
  mill.refreshOverview()
}

async function openDetail(row) {
  actRow.value = row
  detailVisible.value = true
  try {
    actRow.value = await slabApi.detail(row.id)
  } catch (e) {
    // 列表行已足够展示，详情刷新失败不挡抽屉
  }
}

function gotoQuality() {
  detailVisible.value = false
  nav.push('/quality')
}

function openStatus(row) {
  actRow.value = row
  nextStatus.value = row.status
  statusVisible.value = true
}

async function doStatus() {
  await slabApi.setStatus(actRow.value.id, nextStatus.value)
  statusVisible.value = false
  detailVisible.value = false
  ElMessage.success('大板状态已更新')
  load()
}

watch(() => [mill.filters.stoneType, mill.filters.grade, mill.filters.status], load)

onMounted(async () => {
  blocks.value = await blockApi.list()
  await load()
})
</script>

<style scoped>
.hold-banner {
  margin: 4px 0;
  padding: 2px 6px;
  font-size: 11px;
  color: #fff;
  background: var(--mill-danger);
  border-radius: 4px;
  line-height: 1.6;
}
</style>
