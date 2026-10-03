<template>
  <section class="panel">
    <div class="panel-head">
      <h3>出库发货</h3>
      <span class="panel-sub">表格 + 金额汇总，展开行看大板明细；加大板时调用「可发货大板」Criteria 筛选</span>
      <div class="panel-extra">
        <el-select v-model="status" clearable placeholder="发货状态" style="width: 130px" @change="load">
          <el-option label="待装车" value="LOADING" />
          <el-option label="已发运" value="SHIPPED" />
          <el-option label="已签收" value="SIGNED" />
          <el-option label="已结款" value="PAID" />
        </el-select>
        <el-input v-model="keyword" clearable placeholder="单号 / 客户 / 车牌 / 司机" style="width: 220px"
                  @keyup.enter="load" @clear="load" />
        <el-button @click="load">刷新</el-button>
        <el-button type="primary" @click="openCreate">新建发货单</el-button>
      </div>
    </div>

    <div class="summary-bar">
      <div class="summary-cell"><span>发货单</span><b>{{ summary.deliveryCount || 0 }}</b> 单</div>
      <div class="summary-cell"><span>总金额</span><b>{{ summary.totalAmount || 0 }}</b> 元</div>
      <div class="summary-cell"><span>发货面积</span><b>{{ summary.totalArea || 0 }}</b> m²</div>
      <div class="summary-cell"><span>待装车</span><b>{{ summary.loadingCount || 0 }}</b></div>
      <div class="summary-cell" :class="{ 'blocked-summary': summary.blockedCount > 0 }">
        <span>质量挂起禁运</span><b>{{ summary.blockedCount || 0 }}</b> 单
      </div>
      <div class="summary-cell"><span>已发运</span><b>{{ summary.shippedCount || 0 }}</b></div>
      <div class="summary-cell"><span>已签收 / 已结款</span>
        <b>{{ summary.signedCount || 0 }} / {{ summary.paidCount || 0 }}</b>
      </div>
    </div>

    <el-alert v-if="blockedRows.length" type="error" :closable="false" show-icon style="margin-bottom: 10px">
      <template #title>
        有 {{ blockedRows.length }} 张待装车发货单因大板质量隔离被挂起禁运（明细与金额原样保留供核对）：
        <el-tag v-for="r in blockedRows" :key="r.id" size="small" type="danger" effect="plain"
                style="margin: 2px" @click="jumpBlocked(r)" class="link-tag">
          {{ r.deliveryNo }} · {{ r.blockedSlabCodes.join('/') }}
        </el-tag>
      </template>
    </el-alert>

    <el-table ref="tableRef" :data="rows" border stripe size="small" @expand-change="onExpand">
      <el-table-column type="expand">
        <template #default="{ row }">
          <div style="padding: 6px 10px">
            <el-alert v-if="row.qualityBlocked" type="error" :closable="false" show-icon
                      style="margin-bottom: 8px"
                      :title="'质量挂起：大板 ' + row.blockedSlabCodes.join('、') + ' 处于隔离/待复检（' + row.blockedHoldNos.join('、') + '），本单已禁止发运；明细与金额保留，待复检放行后自动解除，判废则自动移除该明细并重算。'" />
            <el-table :data="items[row.id] || []" border size="small" :row-class-name="itemRowClass">
              <el-table-column prop="slabCode" label="大板编号" width="120" />
              <el-table-column label="质量" width="92">
                <template #default="{ row: it }">
                  <el-tag v-if="it.qualityBlocked" size="small" type="danger">
                    ⛔{{ it.qualityHoldStatusName }}
                  </el-tag>
                  <span v-else class="panel-sub">正常</span>
                </template>
              </el-table-column>
              <el-table-column label="等级" width="80">
                <template #default="{ row: it }">
                  <span class="grade-tag" :style="{ background: colorOf(it.grade) }">{{ it.gradeName }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="finishName" label="表面处理" width="90" />
              <el-table-column label="尺寸(cm)" width="130">
                <template #default="{ row: it }">{{ it.lengthCm }}×{{ it.widthCm }}</template>
              </el-table-column>
              <el-table-column prop="quantity" label="数量" width="70" />
              <el-table-column prop="areaM2" label="面积 m²" width="90" />
              <el-table-column prop="unitPrice" label="单价 元/m²" width="110" />
              <el-table-column prop="amount" label="金额 元" width="110" />
              <el-table-column prop="location" label="库位 / 隔离原因" min-width="150">
                <template #default="{ row: it }">
                  {{ it.location }}
                  <div v-if="it.qualityBlocked" class="block-reason">
                    {{ it.qualityHoldNo }} · {{ it.qualityDefectName }}
                  </div>
                </template>
              </el-table-column>
            </el-table>
            <div v-if="!(items[row.id] || []).length" class="empty-tip">该发货单还没有大板明细</div>
          </div>
        </template>
      </el-table-column>

      <el-table-column prop="deliveryNo" label="发货单号" width="140" />
      <el-table-column prop="customer" label="客户" min-width="190" show-overflow-tooltip />
      <el-table-column prop="contact" label="联系人" width="100" />
      <el-table-column prop="plateNo" label="车牌" width="110" />
      <el-table-column prop="driver" label="司机" width="90" />
      <el-table-column prop="deliveryDate" label="发货日期" width="110" />
      <el-table-column label="片数" width="80">
        <template #default="{ row }">{{ row.totalQuantity }}</template>
      </el-table-column>
      <el-table-column label="面积 m²" width="90">
        <template #default="{ row }">{{ row.totalArea }}</template>
      </el-table-column>
      <el-table-column prop="totalAmount" label="总金额 元" width="120" />
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag size="small" :type="tagType(row.status)">{{ row.statusName }}</el-tag>
          <el-tag v-if="row.qualityBlocked" size="small" type="danger" style="margin-top: 2px; display: block">
            质量挂起禁运
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 'LOADING'" link type="primary" @click="openAddItem(row)">
            加大板
          </el-button>
          <el-tooltip v-else :content="addDisabledTip(row.status)" placement="top">
            <span>
              <el-button link type="primary" disabled style="margin-left: 0">加大板</el-button>
            </span>
          </el-tooltip>
          <el-button link :type="row.qualityBlocked ? 'danger' : ''" :disabled="row.qualityBlocked"
                     @click="openStatus(row)">
            {{ row.qualityBlocked ? '禁运中' : '状态流转' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="table-footer">
      <span>发货单 <b>{{ total }}</b> 单</span>
      <span>当前筛选金额合计 <b>{{ summary.totalAmount || 0 }}</b> 元</span>
    </div>

    <div class="pager">
      <el-pagination background layout="total, sizes, prev, pager, next"
                     :total="total" :current-page="page" :page-size="size"
                     :page-sizes="[10, 20, 50]" @current-change="onPage" @size-change="onSize" />
    </div>
  </section>

  <el-dialog v-model="createVisible" title="新建发货单" width="520px">
    <el-form :model="form" label-width="100px">
      <el-form-item label="发货单号"><el-input v-model="form.deliveryNo" placeholder="如 DEL-2026-0613" /></el-form-item>
      <el-form-item label="客户"><el-input v-model="form.customer" /></el-form-item>
      <el-form-item label="联系人"><el-input v-model="form.contact" /></el-form-item>
      <el-form-item label="联系电话"><el-input v-model="form.phone" /></el-form-item>
      <el-form-item label="车牌"><el-input v-model="form.plateNo" placeholder="如 粤A·8H52L" /></el-form-item>
      <el-form-item label="司机"><el-input v-model="form.driver" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="createVisible = false">取消</el-button>
      <el-button type="primary" @click="doCreate">创建</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="itemVisible" :title="'加大板 · ' + (actRow.deliveryNo || '')" width="720px">
    <el-alert v-if="actRow.status !== 'LOADING'" type="warning" :closable="false" show-icon
              style="margin-bottom: 10px"
              :title="'发货单当前为「' + (actRow.statusName || '') + '」，只有待装车单能加大板，请关闭弹窗并刷新列表'" />
    <div style="margin-bottom: 10px">
      <el-input v-model="shipKeyword" clearable placeholder="按编号 / 库位搜可发货大板" style="width: 240px"
                @keyup.enter="loadShippable" @clear="loadShippable" />
      <el-input-number v-model="shipMinArea" :min="0" :step="0.1" :precision="1" controls-position="right"
                       placeholder="面积≥" style="width: 120px; margin-left: 8px" @change="loadShippable" />
      <el-button style="margin-left: 8px" @click="loadShippable">筛选</el-button>
      <span class="panel-sub" style="margin-left: 10px">只列出在库 / 已预定且未被质量隔离冻结的大板</span>
    </div>
    <el-table :data="shippable" border size="small" highlight-current-row @current-change="onPickSlab">
      <el-table-column prop="slabCode" label="大板编号" width="110" />
      <el-table-column label="等级" width="80">
        <template #default="{ row }">
          <span class="grade-tag" :style="{ background: colorOf(row.grade) }">{{ row.gradeName }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="finishName" label="表面处理" width="90" />
      <el-table-column label="尺寸(cm)" width="120">
        <template #default="{ row }">{{ row.lengthCm }}×{{ row.widthCm }}</template>
      </el-table-column>
      <el-table-column prop="areaM2" label="面积 m²" width="90" />
      <el-table-column prop="blockCode" label="荒料" width="120" />
      <el-table-column prop="location" label="库位" width="100" />
      <el-table-column prop="statusName" label="状态" width="90" />
    </el-table>
    <div style="margin-top: 12px">
      <span class="panel-sub">已选：{{ picked ? picked.slabCode + '（' + picked.areaM2 + ' m²）' : '未选择' }}</span>
      <el-input-number v-model="unitPrice" :min="0" :step="10" style="margin-left: 12px" />
      <span class="panel-sub" style="margin-left: 4px">单价 元/m²</span>
    </div>
    <template #footer>
      <el-button @click="itemVisible = false">取消</el-button>
      <el-button type="primary" :loading="itemSaving" :disabled="!picked || actRow.status !== 'LOADING'"
                 @click="doAddItem">加入发货单</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="statusVisible" title="发货状态流转" width="420px">
    <el-alert v-if="actRow.qualityBlocked" type="error" :closable="false" show-icon style="margin-bottom: 12px"
              :title="'该单质量挂起（' + actRow.blockedHoldNos.join('、') + '），禁止发运；请先到质量处置页复检放行或报废。'" />
    <el-form label-width="90px">
      <el-form-item label="发货单"><b>{{ actRow.deliveryNo }}</b></el-form-item>
      <el-form-item label="目标状态">
        <el-select v-model="nextStatus" style="width: 100%">
          <el-option label="待装车" value="LOADING" />
          <el-option label="已发运" value="SHIPPED" :disabled="actRow.qualityBlocked" />
          <el-option label="已签收" value="SIGNED" :disabled="actRow.qualityBlocked" />
          <el-option label="已结款" value="PAID" :disabled="actRow.qualityBlocked" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="statusVisible = false">取消</el-button>
      <el-button type="primary" :disabled="actRow.qualityBlocked && nextStatus !== 'LOADING'"
                 @click="doStatus">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, inject, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { deliveryApi, slabApi } from '../api'

const mill = inject('mill')
const nav = useRouter()

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const summary = ref({})
const status = ref('')
const keyword = ref('')
const items = ref({})

const createVisible = ref(false)
const itemVisible = ref(false)
const statusVisible = ref(false)
const actRow = ref({})
const nextStatus = ref('LOADING')
const form = ref({ deliveryNo: '', customer: '', contact: '', phone: '', plateNo: '', driver: '' })

const shippable = ref([])
const picked = ref(null)
const shipKeyword = ref('')
const shipMinArea = ref(null)
const unitPrice = ref(480)
const itemSaving = ref(false)

function tagType(code) {
  return { LOADING: 'info', SHIPPED: 'warning', SIGNED: 'success', PAID: 'primary' }[code] || 'info'
}

/** 非待装车单加大板禁用时的悬浮说明（明确页面也拒绝增补） */
function addDisabledTip(code) {
  const name = { SHIPPED: '已发运', SIGNED: '已签收', PAID: '已结款' }[code] || '当前状态'
  return name + '的发货单不能再加大板；只有待装车单允许增补明细'
}

function colorOf(grade) {
  return { A: '#43a047', B: '#f9a825', C: '#90a4ae', DEFECT: '#e53935' }[grade] || '#90a4ae'
}

function buildParams() {
  const params = { page: page.value, size: size.value, orderBy: 'date', desc: true }
  if (status.value) params.status = status.value
  if (keyword.value) params.keyword = keyword.value
  return params
}

async function load() {
  const params = buildParams()
  const result = await deliveryApi.page(params)
  rows.value = result.rows
  total.value = result.total
  summary.value = await deliveryApi.summary(params)
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

async function onExpand(row) {
  if (items.value[row.id]) return
  const list = await deliveryApi.items(row.id)
  items.value = { ...items.value, [row.id]: list }
}

const tableRef = ref(null)

function jumpBlocked(r) {
  actRow.value = r
  if (!items.value[r.id]) {
    deliveryApi.items(r.id).then((list) => {
      items.value = { ...items.value, [r.id]: list }
      tableRef.value && tableRef.value.toggleRowExpansion(r, true)
    })
  } else {
    tableRef.value && tableRef.value.toggleRowExpansion(r, true)
  }
  nav.push('/deliveries')
}

function openCreate() {
  form.value = { deliveryNo: '', customer: '', contact: '', phone: '', plateNo: '', driver: '' }
  createVisible.value = true
}

async function doCreate() {
  await deliveryApi.create(form.value)
  createVisible.value = false
  ElMessage.success('发货单已创建')
  load()
}

async function loadShippable() {
  const params = { size: 50, orderBy: 'area', desc: true }
  if (shipKeyword.value) params.keyword = shipKeyword.value
  if (shipMinArea.value) params.minArea = shipMinArea.value
  if (mill.filters.grade) params.grades = mill.filters.grade
  const result = await slabApi.shippable(params)
  shippable.value = result.rows
}

function openAddItem(row) {
  actRow.value = row
  picked.value = null
  unitPrice.value = 480
  itemVisible.value = true
  loadShippable()
}

function onPickSlab(row) {
  picked.value = row
}

async function doAddItem() {
  // 页面兜底：非待装车（弹窗打开后单据被其他人推进状态）直接拒绝，不发请求
  if (actRow.value.status !== 'LOADING') {
    ElMessage.error('发货单已不是待装车状态，不能加大板，请刷新列表')
    itemVisible.value = false
    load()
    return
  }
  if (itemSaving.value || !picked.value) return
  itemSaving.value = true
  try {
    await deliveryApi.addItem(actRow.value.id, { slabId: picked.value.id, quantity: 1, unitPrice: unitPrice.value })
    itemVisible.value = false
    ElMessage.success('大板已加入发货单，金额按 面积×单价 算出')
    // 作废该行旧明细缓存，展开时重新拉取，保证列表 / 明细 / 金额一致
    const refreshed = { ...items.value }
    delete refreshed[actRow.value.id]
    items.value = refreshed
    load()
    mill.refreshOverview()
  } finally {
    itemSaving.value = false
  }
}

function openStatus(row) {
  actRow.value = row
  nextStatus.value = row.status
  statusVisible.value = true
}

async function doStatus() {
  if (actRow.value.qualityBlocked && nextStatus.value !== 'LOADING') {
    ElMessage.error('该发货单质量挂起中，禁止发运；请先到质量处置页完成复检放行或报废')
    return
  }
  await deliveryApi.setStatus(actRow.value.id, nextStatus.value)
  statusVisible.value = false
  ElMessage.success('发货状态已更新')
  const refreshed = { ...items.value }
  delete refreshed[actRow.value.id]
  items.value = refreshed
  load()
  mill.refreshOverview()
}

/** 被质量挂起的发货单（顶部红条用） */
const blockedRows = computed(() => rows.value.filter((r) => r.qualityBlocked))

/** 明细行：被挂起的整行打红底 */
function itemRowClass({ row }) {
  return row.qualityBlocked ? 'row-quality-blocked' : ''
}

onMounted(load)
</script>

<style scoped>
.blocked-summary {
  border-left-color: var(--mill-danger);
}
.blocked-summary b {
  color: var(--mill-danger);
}
.link-tag {
  cursor: pointer;
}
.block-reason {
  font-size: 11px;
  color: var(--mill-danger);
}
:deep(.row-quality-blocked) {
  background: #fdecea !important;
}
:deep(.row-quality-blocked td) {
  color: var(--mill-danger);
}
</style>
