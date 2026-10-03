<template>
  <div class="shell">
    <!-- 顶部：品牌 + 全站筛选（石种 / 等级 / 状态）+ 计数器 -->
    <header class="topbar">
      <div class="brand">
        <span class="brand-mark">▦</span>
        <div>
          石材加工厂 · 大板加工生产管理系统
          <div class="brand-sub">
            {{ todayText }} · 在库大板 {{ overview.instockSlab || 0 }} 片 / {{ overview.slabArea || 0 }} m²
          </div>
        </div>
      </div>

      <div class="global-filter">
        <el-select v-model="filters.stoneType" clearable placeholder="石种" @change="onFilterChange">
          <el-option v-for="s in stoneTypes" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
        <el-select v-model="filters.grade" clearable placeholder="等级" @change="onFilterChange">
          <el-option v-for="g in grades" :key="g.value" :label="g.label" :value="g.value" />
        </el-select>
        <el-select v-model="filters.status" clearable placeholder="状态" @change="onFilterChange">
          <el-option v-for="s in slabStatuses" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
      </div>

      <div class="top-stats">
        <div class="stat-chip"><b>{{ overview.blockCount || 0 }}</b><span>荒料</span></div>
        <div class="stat-chip"><b>{{ overview.blockVolume || 0 }}</b><span>体积 m³</span></div>
        <div class="stat-chip"><b>{{ overview.slabCount || 0 }}</b><span>大板</span></div>
        <div class="stat-chip"><b>{{ overview.orderProcessing || 0 }}</b><span>加工中工单</span></div>
        <div class="stat-chip">
          <b>{{ overview.avgWasteRate || 0 }}%</b><span>平均损耗</span></div>
        <div class="stat-chip"><b>{{ money(overview.deliveryAmount) }}</b><span>发货金额</span></div>
      </div>
    </header>

    <nav class="mod-nav">
      <router-link v-for="n in navItems" :key="n.key" :to="n.path" class="mod-nav-item">
        <span class="mod-icon">{{ n.icon }}</span>
        <span class="mod-label">{{ n.label }}</span>
      </router-link>
    </nav>

    <div class="body">
      <!-- 左侧树：荒料批次 → 大板 -->
      <aside class="side">
        <div class="side-title">
          <span>荒料批次 / 大板</span>
          <el-button link type="primary" @click="loadTree">刷新</el-button>
        </div>
        <div class="side-hint">点荒料看排版图，点大板看详情</div>

        <el-tree
          v-loading="treeLoading"
          :data="tree"
          node-key="key"
          :props="{ children: 'children', label: 'label' }"
          :default-expanded-keys="expandedKeys"
          :expand-on-click-node="false"
          highlight-current
          @node-click="onNodeClick"
        >
          <template #default="{ data }">
            <span class="tree-node">
              <span v-if="data.type === 'block'" class="tree-dot">▣</span>
              <span v-else class="tree-dot" :style="{ background: gradeColor(data.grade) }"></span>
              <span>{{ data.label }}</span>
            </span>
          </template>
        </el-tree>
      </aside>

      <main class="stage">
        <router-view />
      </main>
    </div>

    <!-- 点树上大板节点：抽屉看大板详情 -->
    <el-drawer v-model="slabVisible" title="大板详情" size="360px">
      <div v-if="slab.id">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="大板编号">{{ slab.slabCode }}</el-descriptions-item>
          <el-descriptions-item label="所属荒料">{{ slab.blockCode }}</el-descriptions-item>
          <el-descriptions-item label="石种">{{ slab.stoneTypeName }}</el-descriptions-item>
          <el-descriptions-item label="尺寸">
            {{ slab.lengthCm }} × {{ slab.widthCm }} × {{ slab.thicknessCm }} cm
          </el-descriptions-item>
          <el-descriptions-item label="面积">{{ slab.areaM2 }} m²</el-descriptions-item>
          <el-descriptions-item label="表面处理">{{ slab.finishName }}</el-descriptions-item>
          <el-descriptions-item label="质量等级">
            <span class="grade-tag" :style="{ background: gradeColor(slab.grade) }">{{ slab.gradeName }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="库位">{{ slab.location }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ slab.statusName }}</el-descriptions-item>
          <el-descriptions-item v-if="slab.qualityHold" label="质量隔离">
            <el-tag size="small" type="danger">{{ slab.qualityHoldStatusName }} · {{ slab.qualityDefectName }}</el-tag>
            <div class="panel-sub">{{ slab.qualityHoldNo }} · 发货冻结中</div>
          </el-descriptions-item>
          <el-descriptions-item label="锯切谱系">
            <div v-if="slab.sawTracked">
              归属批次 <b>{{ slab.sawBatchNo }}</b>（{{ slab.sawBatchStatusName }}）
            </div>
            <span v-else class="panel-sub">未挂锯切产出（批次功能前的旧大板或尚未交接）</span>
          </el-descriptions-item>
          <el-descriptions-item label="备注">{{ slab.remark || '—' }}</el-descriptions-item>
        </el-descriptions>

        <!-- 锯切批次反查：所属批次 + 历次更正 -->
        <div v-if="sawTrace && sawTrace.outputs && sawTrace.outputs.length" class="saw-trace">
          <div class="saw-trace-title">锯切谱系反查</div>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="当前归属">
              <template v-if="sawTrace.currentBatchNo">
                <b>{{ sawTrace.currentBatchNo }}</b> · {{ sawTrace.currentBatchStatusName }}
                · {{ sawTrace.currentOutputTypeName }} · 第 {{ sawTrace.currentSeqNo || '—' }} 序
              </template>
              <span v-else class="panel-sub">当前无有效归属（产出已作废 / 仅历史记录）</span>
            </el-descriptions-item>
          </el-descriptions>
          <el-table :data="sawTrace.outputs" size="small" border style="margin-top: 8px">
            <el-table-column label="批次">
              <template #default="{ row }">{{ batchNoOf(row.batchId) }}</template>
            </el-table-column>
            <el-table-column label="顺序" width="60" align="center">
              <template #default="{ row }">{{ row.seqNo || '—' }}</template>
            </el-table-column>
            <el-table-column prop="outputTypeName" label="类型" width="92" />
            <el-table-column label="尺寸" width="120">
              <template #default="{ row }">{{ row.actualLength ? row.actualLength + '×' + row.actualWidth : '—' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="76">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ row.statusName }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="登记时间" width="150">
              <template #default="{ row }">{{ fmtTrace(row.registeredAt) }}</template>
            </el-table-column>
          </el-table>
          <div class="saw-trace-title" style="margin-top: 10px">历次更正 / 留痕</div>
          <el-timeline>
            <el-timeline-item v-for="a in sawTrace.audits" :key="a.id"
                              :timestamp="(a.operator || '') + ' · ' + fmtTrace(a.createdAt)" placement="top">
              <b>{{ a.eventTypeName }}</b> <span class="panel-sub">{{ a.batchNo }}</span>
              <div v-if="a.fromContent" class="trace-from">变更前：{{ a.fromContent }}</div>
              <div v-if="a.toContent">变更后：{{ a.toContent }}</div>
              <div v-if="a.reason" class="trace-reason">原因：{{ a.reason }}</div>
            </el-timeline-item>
          </el-timeline>
        </div>

        <div style="margin-top: 14px">
          <el-button type="warning" @click="gotoQuality">质量处置</el-button>
          <el-button type="primary" @click="gotoSlab">到大板库存页</el-button>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, getCurrentInstance, onMounted, provide, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import router, { navItems } from './router'
import { millApi, slabApi, sawApi } from './api'

/**
 * 模板自带的 main.js 只装了 Element Plus，没装 router（那份文件不动它），
 * 所以在这里把 router 装到根组件所属的 app 实例上。
 * vue-router 的 install 自带幂等保护，重复调用无副作用。
 */
getCurrentInstance().appContext.app.use(router)

const route = useRoute()
const nav = useRouter()

/** 词典：与后端 StoneDict 保持一致 */
const stoneTypes = [
  { value: 'MARBLE', label: '大理石' },
  { value: 'GRANITE', label: '花岗岩' },
  { value: 'JADE', label: '玉石' },
  { value: 'QUARTZ', label: '石英石' },
  { value: 'TRAVERTINE', label: '洞石' }
]
const grades = [
  { value: 'A', label: 'A 级' },
  { value: 'B', label: 'B 级' },
  { value: 'C', label: 'C 级' },
  { value: 'DEFECT', label: '次品' }
]
const slabStatuses = [
  { value: 'INSTOCK', label: '在库' },
  { value: 'RESERVED', label: '已预定' },
  { value: 'OUTBOUND', label: '已出库' },
  { value: 'SCRAP', label: '报废' }
]

const filters = reactive({ stoneType: '', grade: '', status: '' })
const overview = ref({})
const tree = ref([])
const treeLoading = ref(false)
const selectedBlockId = ref(null)
const slabVisible = ref(false)
const slab = ref({})
const sawTrace = ref(null)

/** 全局上下文：所有页面 inject('mill') */
const mill = { filters, overview, tree, selectedBlockId, refreshOverview: loadOverview, refreshTree: loadTree }
provide('mill', mill)

const todayText = computed(() => {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
})

const expandedKeys = computed(() => tree.value.slice(0, 3).map((n) => n.key))

function gradeColor(grade) {
  return { A: '#43a047', B: '#f9a825', C: '#90a4ae', DEFECT: '#e53935' }[grade] || '#90a4ae'
}

function money(v) {
  const n = Number(v || 0)
  return n >= 10000 ? (n / 10000).toFixed(2) + ' 万' : n.toFixed(0)
}

function onFilterChange() {
  loadTree()
}

async function loadOverview() {
  overview.value = await millApi.overview()
}

async function loadTree() {
  treeLoading.value = true
  try {
    const params = {}
    if (filters.stoneType) params.stoneType = filters.stoneType
    if (filters.grade) params.grade = filters.grade
    if (filters.status) params.status = filters.status
    tree.value = await millApi.tree(params)
  } finally {
    treeLoading.value = false
  }
}

async function onNodeClick(data) {
  if (data.type === 'block') {
    selectedBlockId.value = data.id
    if (route.path !== '/layout') nav.push('/layout')
    return
  }
  const detail = await slabApi.detail(data.id)
  slab.value = detail
  sawTrace.value = null
  // 锯切谱系反查（无产出时后端返回空 outputs，不阻断详情）
  try {
    sawTrace.value = await sawApi.traceBySlab(data.id)
  } catch (e) {
    sawTrace.value = null
  }
  slabVisible.value = true
}

function fmtTrace(v) {
  return v ? String(v).replace('T', ' ').slice(0, 16) : '—'
}

function batchNoOf(batchId) {
  if (!sawTrace.value) return ''
  const found = (sawTrace.value.audits || []).find((a) => a.batchId === batchId)
  return found ? found.batchNo : ('#' + batchId)
}

function gotoSlab() {
  slabVisible.value = false
  nav.push('/slabs')
}

function gotoQuality() {
  slabVisible.value = false
  nav.push('/quality')
}

onMounted(async () => {
  await loadOverview()
  await loadTree()
})
</script>

<style scoped>
.tree-node {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}

.tree-dot {
  display: inline-block;
  width: 9px;
  height: 9px;
  border-radius: 2px;
  background: var(--mill-primary);
  font-size: 10px;
  line-height: 9px;
  color: var(--mill-primary);
}

.saw-trace {
  margin-top: 14px;
  border-top: 1px dashed #cfd8dc;
  padding-top: 10px;
}

.saw-trace-title {
  font-weight: 600;
  font-size: 13px;
  margin-bottom: 6px;
  color: var(--mill-primary);
}

.trace-from {
  color: #90a4ae;
  font-size: 12px;
}

.trace-reason {
  color: #e6a23c;
  font-size: 12px;
}
</style>
