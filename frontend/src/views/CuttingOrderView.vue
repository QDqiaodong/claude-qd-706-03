<template>
  <section class="panel">
    <div class="panel-head">
      <h3>加工工单</h3>
      <span class="panel-sub">锯切方式 · 计划板数 / 已完成板数 · 进度条</span>
      <div class="panel-extra">
        <el-select v-model="sawType" clearable placeholder="锯切方式" style="width: 130px" @change="load">
          <el-option label="框架锯" value="FRAME" />
          <el-option label="圆盘锯" value="DISC" />
          <el-option label="绳锯" value="WIRE" />
          <el-option label="组合锯" value="COMBO" />
        </el-select>
        <el-select v-model="status" clearable placeholder="工单状态" style="width: 130px" @change="load">
          <el-option label="待排产" value="PENDING" />
          <el-option label="加工中" value="PROCESSING" />
          <el-option label="已完工" value="DONE" />
          <el-option label="已结算" value="SETTLED" />
        </el-select>
        <el-button @click="load">刷新</el-button>
        <el-button type="primary" @click="openCreate">新建工单</el-button>
      </div>
    </div>

    <el-table :data="rows" border stripe size="small">
      <el-table-column prop="orderNo" label="工单号" width="140" />
      <el-table-column prop="blockCode" label="荒料" width="130" />
      <el-table-column label="石种" width="90">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ row.stoneTypeName }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="sawTypeName" label="锯切方式" width="100" />
      <el-table-column label="板数（已完成/计划）" width="170">
        <template #default="{ row }">
          <b>{{ row.doneCount }}</b> / {{ row.planCount }}
          <div v-if="row.sawTracked" class="panel-sub">
            基线 {{ row.sawBaselineCount }} + 已收口合格 {{ row.sawQualifiedCount }}
            <el-tag v-if="row.sawOpenDiffCount > 0" size="small" type="danger" effect="plain">
              {{ row.sawOpenDiffCount }} 差异待核
            </el-tag>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="进度" min-width="200">
        <template #default="{ row }">
          <el-progress :percentage="row.progress" :stroke-width="14" :color="barColor(row.progress)"
                       :format="() => row.progress + '%'" />
        </template>
      </el-table-column>
      <el-table-column prop="startDate" label="开工日期" width="110" />
      <el-table-column prop="finishDate" label="完工日期" width="110">
        <template #default="{ row }">{{ row.finishDate || '—' }}</template>
      </el-table-column>
      <el-table-column prop="team" label="班组" width="80" />
      <el-table-column label="交接口径" width="118">
        <template #default="{ row }">
          <el-tag v-if="row.sawTracked" size="small" type="success" effect="plain">锯切谱系</el-tag>
          <el-tooltip v-else content="批次功能上线前的旧工单，仍可手工登记完成数，尚未补锯切交接记录" placement="top">
            <el-tag size="small" type="info" effect="plain">待补交接</el-tag>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="tagType(row.status)">{{ row.statusName }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="290" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="doStart(row)" v-if="row.status === 'PENDING'">开工</el-button>
          <el-button link type="success" @click="doProgress(row)"
                     v-if="!row.sawTracked && (row.status === 'PENDING' || row.status === 'PROCESSING')">完成+1</el-button>
          <el-button link type="warning" @click="doFinish(row)"
                     v-if="!row.sawTracked && (row.status === 'PENDING' || row.status === 'PROCESSING')">完工</el-button>
          <el-button link @click="doSettle(row)" v-if="row.status === 'DONE'">结算</el-button>
          <el-button link type="primary" @click="openBatches(row)">批次/谱系</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="table-footer">
      <span>工单 <b>{{ total }}</b> 张</span>
      <span>已完工板数 <b>{{ doneTotal }}</b> 片</span>
    </div>

    <div class="pager">
      <el-pagination background layout="total, sizes, prev, pager, next"
                     :total="total" :current-page="page" :page-size="size"
                     :page-sizes="[10, 20, 50]" @current-change="onPage" @size-change="onSize" />
    </div>
  </section>

  <el-dialog v-model="createVisible" title="新建加工工单" width="500px">
    <el-form :model="form" label-width="100px">
      <el-form-item label="工单号"><el-input v-model="form.orderNo" placeholder="如 CO-2026-0013" /></el-form-item>
      <el-form-item label="荒料">
        <el-select v-model="form.blockId" filterable style="width: 100%">
          <el-option v-for="b in blocks" :key="b.id" :label="b.blockCode + ' · ' + b.stoneTypeName" :value="b.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="锯切方式">
        <el-select v-model="form.sawType" style="width: 100%">
          <el-option label="框架锯" value="FRAME" />
          <el-option label="圆盘锯" value="DISC" />
          <el-option label="绳锯" value="WIRE" />
          <el-option label="组合锯" value="COMBO" />
        </el-select>
      </el-form-item>
      <el-form-item label="计划板数"><el-input-number v-model="form.planCount" :min="1" :step="1" /></el-form-item>
      <el-form-item label="班组">
        <el-select v-model="form.team" style="width: 100%">
          <el-option label="甲班" value="甲班" />
          <el-option label="乙班" value="乙班" />
          <el-option label="丙班" value="丙班" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="createVisible = false">取消</el-button>
      <el-button type="primary" @click="doCreate">创建</el-button>
    </template>
  </el-dialog>

  <!-- 工单的锯切批次 / 待核差异 / 逐片来源 -->
  <el-drawer v-model="batchDrawerVisible" size="60%"
             :title="batchOrder.id ? `锯切批次谱系 · ${batchOrder.orderNo}（${batchOrder.blockCode}）` : '锯切批次谱系'">
    <div v-if="batchOrder.id">
      <el-alert v-if="!batchOrder.sawTracked" type="info" :closable="false" show-icon style="margin-bottom: 10px"
                title="该工单是批次功能上线前的旧工单，尚无锯切批次记录，页面按原手工完成数查看（已标「待补交接」）。可到「锯切批次」页对该工单开锯建批，建批会固化当前完成数为基线。" />
      <el-alert v-else type="success" :closable="false" show-icon style="margin-bottom: 10px"
                :title="`完成数口径：基线 ${batchOrder.sawBaselineCount} + 已收口批次合格实产 ${batchOrder.sawQualifiedCount} = ${batchOrder.doneCount}；未收口/待核批次的实产不计入。`" />

      <el-table :data="batchList" border size="small">
        <el-table-column prop="batchNo" label="批次号" width="130" />
        <el-table-column label="状态" width="104">
          <template #default="{ row }">
            <el-tag size="small" :type="batchStatusType(row.status)">{{ row.statusName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="实产（成品/余料/锯耗）" width="180">
          <template #default="{ row }">
            {{ row.activeFinishedCount }} / {{ row.activeRemnantCount }} / {{ row.activeSawLossCount }}
            <span class="panel-sub">（合格 {{ row.activeQualifiedCount }}）</span>
          </template>
        </el-table-column>
        <el-table-column label="差异" width="100" align="center">
          <template #default="{ row }">
            <el-badge v-if="row.openDiffCount > 0" :value="row.openDiffCount" type="danger">
              <el-tag size="small" type="danger">待核</el-tag>
            </el-badge>
            <el-tag v-else size="small" type="success">核清</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="交接 / 收口时间" min-width="260">
          <template #default="{ row }">
            <div class="panel-sub">交接：{{ fmt(row.handoverAt) }}</div>
            <div class="panel-sub">收口：{{ fmt(row.closedAt) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="gotoBatch(row)">逐片来源</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="batchOrder.sawTracked && !batchList.length" class="panel-sub" style="margin-top: 10px">
        已纳入批次口径但暂无批次数据。
      </div>
    </div>
  </el-drawer>
</template>

<script setup>
import { computed, inject, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { blockApi, orderApi, sawApi } from '../api'

const mill = inject('mill')
const nav = useRouter()

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const sawType = ref('')
const status = ref('')
const blocks = ref([])
const createVisible = ref(false)
const form = ref({ orderNo: '', blockId: null, sawType: 'FRAME', planCount: 4, team: '甲班' })

const doneTotal = computed(() => rows.value.reduce((sum, r) => sum + (r.doneCount || 0), 0))

function tagType(code) {
  return { PENDING: 'info', PROCESSING: 'warning', DONE: 'success', SETTLED: 'primary' }[code] || 'info'
}

function barColor(p) {
  if (p >= 100) return '#43a047'
  if (p >= 50) return '#455a64'
  return '#f9a825'
}

async function load() {
  const params = { page: page.value, size: size.value, orderBy: 'date', desc: true }
  if (sawType.value) params.sawType = sawType.value
  if (status.value) params.status = status.value
  if (mill.selectedBlockId && mill.selectedBlockId.value) params.blockId = mill.selectedBlockId.value
  const result = await orderApi.page(params)
  rows.value = result.rows
  total.value = result.total
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
    orderNo: '', blockId: blocks.value[0] ? blocks.value[0].id : null,
    sawType: 'FRAME', planCount: 4, team: '甲班'
  }
  createVisible.value = true
}

async function doCreate() {
  await orderApi.create(form.value)
  createVisible.value = false
  ElMessage.success('加工工单已创建')
  load()
  mill.refreshOverview()
}

async function doStart(row) {
  await orderApi.start(row.id)
  ElMessage.success('工单已开工，荒料置为加工中')
  load()
  mill.refreshOverview()
}

async function doProgress(row) {
  try {
    await orderApi.progress(row.id, 1)
    ElMessage.success('已完成板数 +1')
    load()
  } catch (e) {
    // 已纳入批次的工单后端会拒绝手工 +1，提示去批次页
    if (e && e.message && e.message.includes('批次')) ElMessage.warning(e.message)
  }
}

async function doFinish(row) {
  try {
    await orderApi.finish(row.id)
    ElMessage.success('工单已完工，荒料置为已完料')
    load()
    mill.refreshOverview()
  } catch (e) {
    if (e && e.message && e.message.includes('批次')) ElMessage.warning(e.message)
  }
}

async function doSettle(row) {
  await orderApi.settle(row.id)
  ElMessage.success('工单已结算')
  load()
}

// ---------------- 锯切批次谱系 ----------------
const batchDrawerVisible = ref(false)
const batchOrder = ref({})
const batchList = ref([])

function batchStatusType(s) {
  return { OPEN: 'warning', PENDING_RECONCILE: 'danger', READY: 'success', CLOSED: 'info' }[s] || 'info'
}
function fmt(v) {
  return v ? String(v).replace('T', ' ').slice(0, 16) : '—'
}

async function openBatches(row) {
  batchOrder.value = row
  batchList.value = await sawApi.byOrder(row.id)
  batchDrawerVisible.value = true
}

function gotoBatch(row) {
  batchDrawerVisible.value = false
  nav.push({ path: '/saw', query: { batch: row.id } })
}

watch(() => mill.selectedBlockId.value, load)

onMounted(async () => {
  blocks.value = await blockApi.list()
  await load()
})
</script>
