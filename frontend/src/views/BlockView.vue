<template>
  <section class="panel">
    <div class="panel-head">
      <h3>荒料台账</h3>
      <span class="panel-sub">Criteria 动态筛选：石种 / 状态 / 编号模糊 / 体积区间 / 长宽下限，底部汇总体积与重量</span>
      <div class="panel-extra">
        <el-select v-model="status" clearable placeholder="荒料状态" style="width: 130px" @change="load">
          <el-option label="待排产" value="PENDING" />
          <el-option label="加工中" value="PROCESSING" />
          <el-option label="已完料" value="DONE" />
          <el-option label="结库" value="ARCHIVED" />
        </el-select>
        <el-input v-model="keyword" clearable placeholder="编号 / 矿口 / 供应商" style="width: 200px"
                  @keyup.enter="load" @clear="load" />
        <el-input-number v-model="minVolume" :min="0" :step="1" controls-position="right"
                         placeholder="体积≥" style="width: 120px" @change="load" />
        <el-select v-model="orderBy" style="width: 120px" @change="load">
          <el-option label="按编号" value="code" />
          <el-option label="按体积" value="volume" />
          <el-option label="按重量" value="weight" />
          <el-option label="按进厂日" value="date" />
        </el-select>
        <el-button @click="load">刷新</el-button>
        <el-button type="primary" @click="openCreate">登记荒料</el-button>
      </div>
    </div>

    <el-table :data="rows" border stripe size="small" @row-click="pickBlock">
      <el-table-column prop="blockCode" label="荒料编号" width="130" />
      <el-table-column prop="quarry" label="矿口产地" width="150" show-overflow-tooltip />
      <el-table-column label="石种" width="90">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ row.stoneTypeName }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="长×宽×高(cm)" width="140">
        <template #default="{ row }">{{ row.lengthCm }}×{{ row.widthCm }}×{{ row.heightCm }}</template>
      </el-table-column>
      <el-table-column prop="volumeM3" label="体积 m³" width="90" />
      <el-table-column prop="weightTon" label="重量 t" width="90" />
      <el-table-column prop="inboundDate" label="进厂日期" width="110" />
      <el-table-column prop="purchasePrice" label="单价 元/m³" width="110" />
      <el-table-column prop="purchaseAmount" label="采购金额" width="110" />
      <el-table-column prop="supplier" label="供应商" min-width="170" show-overflow-tooltip />
      <el-table-column label="出板" width="110">
        <template #default="{ row }">{{ row.slabCount }} 片 / {{ row.slabArea }} m²</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="tagType(row.status)">{{ row.statusName }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click.stop="openStatus(row)">状态变更</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="table-footer">
      <span>荒料 <b>{{ summary.blockCount || 0 }}</b> 块</span>
      <span>体积合计 <b>{{ summary.totalVolume || 0 }}</b> m³</span>
      <span>重量合计 <b>{{ summary.totalWeight || 0 }}</b> 吨</span>
      <span>采购金额 <b>{{ summary.purchaseAmount || 0 }}</b> 元</span>
      <span>待排产 <b>{{ summary.pendingCount || 0 }}</b> · 加工中 <b>{{ summary.processingCount || 0 }}</b>
        · 已完料 <b>{{ summary.doneCount || 0 }}</b> · 结库 <b>{{ summary.archivedCount || 0 }}</b></span>
    </div>

    <div class="pager">
      <el-pagination background layout="total, sizes, prev, pager, next"
                     :total="total" :current-page="page" :page-size="size"
                     :page-sizes="[10, 20, 50]" @current-change="onPage" @size-change="onSize" />
    </div>
  </section>

  <el-dialog v-model="createVisible" title="登记荒料" width="560px">
    <el-form :model="form" label-width="110px">
      <el-form-item label="荒料编号"><el-input v-model="form.blockCode" placeholder="如 BLK-2026-013" /></el-form-item>
      <el-form-item label="矿口产地"><el-input v-model="form.quarry" placeholder="如 福建南安水头矿口" /></el-form-item>
      <el-form-item label="石种">
        <el-select v-model="form.stoneType" style="width: 100%">
          <el-option v-for="s in stoneTypes" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="长/宽/高(cm)">
        <el-input-number v-model="form.lengthCm" :min="1" :step="10" />
        <el-input-number v-model="form.widthCm" :min="1" :step="10" style="margin-left: 8px" />
        <el-input-number v-model="form.heightCm" :min="1" :step="10" style="margin-left: 8px" />
      </el-form-item>
      <el-form-item label="采购单价"><el-input-number v-model="form.purchasePrice" :min="0" :step="100" /></el-form-item>
      <el-form-item label="供应商"><el-input v-model="form.supplier" /></el-form-item>
      <el-form-item label="备注"><el-input v-model="form.remark" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="createVisible = false">取消</el-button>
      <el-button type="primary" @click="doCreate">登记</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="statusVisible" title="荒料状态变更" width="400px">
    <el-form label-width="90px">
      <el-form-item label="荒料"><b>{{ actRow.blockCode }}</b></el-form-item>
      <el-form-item label="目标状态">
        <el-select v-model="nextStatus" style="width: 100%">
          <el-option label="待排产" value="PENDING" />
          <el-option label="加工中" value="PROCESSING" />
          <el-option label="已完料" value="DONE" />
          <el-option label="结库" value="ARCHIVED" />
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
import { ElMessage } from 'element-plus'
import { blockApi } from '../api'

const mill = inject('mill')

const stoneTypes = [
  { value: 'MARBLE', label: '大理石' },
  { value: 'GRANITE', label: '花岗岩' },
  { value: 'JADE', label: '玉石' },
  { value: 'QUARTZ', label: '石英石' },
  { value: 'TRAVERTINE', label: '洞石' }
]

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const summary = ref({})
const status = ref('')
const keyword = ref('')
const minVolume = ref(null)
const orderBy = ref('code')

const createVisible = ref(false)
const statusVisible = ref(false)
const actRow = ref({})
const nextStatus = ref('PENDING')
const form = ref({
  blockCode: '', quarry: '', stoneType: 'MARBLE',
  lengthCm: 320, widthCm: 180, heightCm: 150,
  purchasePrice: 3200, supplier: '', remark: ''
})

function tagType(code) {
  return { PENDING: 'info', PROCESSING: 'warning', DONE: 'success', ARCHIVED: 'primary' }[code] || 'info'
}

function pickBlock(row) {
  mill.selectedBlockId.value = row.id
}

function buildParams() {
  const params = { page: page.value, size: size.value, orderBy: orderBy.value, desc: orderBy.value !== 'code' }
  if (mill.filters.stoneType) params.stoneType = mill.filters.stoneType
  if (status.value) params.status = status.value
  if (keyword.value) params.keyword = keyword.value
  if (minVolume.value) params.minVolume = minVolume.value
  return params
}

async function load() {
  const params = buildParams()
  const result = await blockApi.page(params)
  rows.value = result.rows
  total.value = result.total
  summary.value = await blockApi.summary(params)
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
    blockCode: '', quarry: '', stoneType: 'MARBLE',
    lengthCm: 320, widthCm: 180, heightCm: 150,
    purchasePrice: 3200, supplier: '', remark: ''
  }
  createVisible.value = true
}

async function doCreate() {
  await blockApi.create(form.value)
  createVisible.value = false
  ElMessage.success('荒料已登记，体积与重量按石种密度自动算出')
  load()
  mill.refreshOverview()
}

function openStatus(row) {
  actRow.value = row
  nextStatus.value = row.status
  statusVisible.value = true
}

async function doStatus() {
  await blockApi.setStatus(actRow.value.id, nextStatus.value)
  statusVisible.value = false
  ElMessage.success('荒料状态已更新')
  load()
}

watch(() => mill.filters.stoneType, load)

onMounted(load)
</script>
