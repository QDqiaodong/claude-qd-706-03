<template>
  <section class="panel">
    <div class="panel-head">
      <h3>质量处置 · 大板隔离与复检放行</h3>
      <span class="panel-sub">隔离中 → 待复检 → 已放行 / 已报废受控流转；隔离期间发货冻结，复检结论只追加留痕</span>
      <div class="panel-extra">
        <el-select v-model="filters.status" clearable placeholder="记录状态" style="width: 120px" @change="reload">
          <el-option label="隔离中" value="ISOLATED" />
          <el-option label="待复检" value="PENDING_RECHECK" />
          <el-option label="已放行" value="RELEASED" />
          <el-option label="已报废" value="SCRAPPED" />
        </el-select>
        <el-select v-model="filters.defectType" clearable placeholder="缺陷类型" style="width: 110px" @change="reload">
          <el-option v-for="d in defectTypes" :key="d.value" :label="d.label" :value="d.value" />
        </el-select>
        <el-select v-model="filters.severity" clearable placeholder="严重程度" style="width: 110px" @change="reload">
          <el-option label="轻微" value="MINOR" />
          <el-option label="较重" value="MAJOR" />
          <el-option label="严重" value="CRITICAL" />
        </el-select>
        <el-input v-model="filters.blockCode" clearable placeholder="荒料编号" style="width: 130px"
                  @keyup.enter="reload" @clear="reload" />
        <el-input v-model="filters.slabCode" clearable placeholder="大板编号" style="width: 120px"
                  @keyup.enter="reload" @clear="reload" />
        <el-button @click="reload">刷新</el-button>
        <el-button type="warning" @click="openCreate">质量隔离登记</el-button>
      </div>
    </div>

    <div class="summary-bar">
      <div class="summary-cell"><span>隔离中</span><b>{{ summary.isolatedCount || 0 }}</b></div>
      <div class="summary-cell"><span>待复检</span><b>{{ summary.pendingRecheckCount || 0 }}</b></div>
      <div class="summary-cell"><span>已放行</span><b>{{ summary.releasedCount || 0 }}</b></div>
      <div class="summary-cell"><span>已报废</span><b>{{ summary.scrappedCount || 0 }}</b></div>
      <div class="summary-cell block-cell">
        <span>质量挂起待装车单</span><b>{{ summary.blockedDeliveryCount || 0 }}</b> 单禁运
      </div>
    </div>

    <el-table :data="rows" border stripe size="small" v-loading="loading">
      <el-table-column prop="holdNo" label="记录号" width="130" />
      <el-table-column label="轮次" width="64" align="center">
        <template #default="{ row }">第{{ row.roundNo }}轮</template>
      </el-table-column>
      <el-table-column label="大板 / 荒料" width="160">
        <template #default="{ row }">
          <div><b>{{ row.snapSlabCode }}</b></div>
          <div class="panel-sub">{{ row.snapBlockCode || '—' }}</div>
        </template>
      </el-table-column>
      <el-table-column prop="defectTypeName" label="缺陷" width="80" />
      <el-table-column label="严重度" width="86">
        <template #default="{ row }">
          <el-tag size="small" :type="severityType(row.severity)">{{ row.severityName }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="affectPosition" label="影响位置" min-width="150" show-overflow-tooltip />
      <el-table-column label="当前阶段" width="104">
        <template #default="{ row }">
          <el-tag size="small" :type="holdStatusType(row.status)">{{ row.statusName }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="阻断的发货单" min-width="170">
        <template #default="{ row }">
          <template v-if="row.active && row.blockedDeliveries.length">
            <el-tag v-for="d in row.blockedDeliveries" :key="d.deliveryId"
                    size="small" type="danger" effect="plain" class="block-tag">
              {{ d.deliveryNo }} 禁运
            </el-tag>
          </template>
          <span v-else-if="row.active" class="panel-sub">未挂发货单（选板已冻结）</span>
          <span v-else class="panel-sub">已收口</span>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="建立时间" width="158">
        <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="210" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情/时间线</el-button>
          <el-button v-if="row.status === 'ISOLATED'" link type="warning" @click="openDetail(row, 'recheck')">送复检</el-button>
          <el-button v-if="row.status === 'PENDING_RECHECK'" link type="success" @click="openDetail(row, 'recheck')">复检</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination background layout="total, sizes, prev, pager, next"
                     :total="total" :current-page="page" :page-size="size"
                     :page-sizes="[10, 20, 50]" @current-change="onPage" @size-change="onSize" />
    </div>
  </section>

  <!-- 登记质量隔离 -->
  <el-dialog v-model="createVisible" title="质量隔离登记" width="640px">
    <el-alert type="warning" :closable="false" show-icon style="margin-bottom: 12px"
              title="提交后立即冻结大板发货：不可再选入发货单；若已在待装车发货单中，该单显示质量挂起并禁止发运。" />
    <el-form :model="form" label-width="96px">
      <el-form-item label="大板" required>
        <el-select v-model="form.slabId" filterable remote :remote-method="searchSlabs"
                   :loading="slabLoading" placeholder="输入大板编号 / 库位搜索" style="width: 100%"
                   @change="onPickSlab">
          <el-option v-for="s in slabOptions" :key="s.id"
                     :label="s.slabCode + ' · ' + s.blockCode + ' · ' + s.location + ' · ' + s.statusName"
                     :value="s.id" :disabled="s.qualityHold">
            <span>{{ s.slabCode }} · {{ s.blockCode }} · {{ s.location }}</span>
            <el-tag v-if="s.qualityHold" size="small" type="danger" style="margin-left: 8px">
              已隔离 {{ s.qualityHoldStatusName }}
            </el-tag>
            <el-tag v-else size="small" style="margin-left: 8px">{{ s.statusName }}</el-tag>
          </el-option>
        </el-select>
      </el-form-item>
      <el-form-item label="缺陷类型" required>
        <el-radio-group v-model="form.defectType">
          <el-radio-button v-for="d in defectTypes" :key="d.value" :value="d.value">{{ d.label }}</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="严重程度" required>
        <el-radio-group v-model="form.severity">
          <el-radio-button value="MINOR">轻微</el-radio-button>
          <el-radio-button value="MAJOR">较重</el-radio-button>
          <el-radio-button value="CRITICAL">严重</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="影响位置">
        <el-input v-model="form.affectPosition" placeholder="如 左上角距边 10cm / 板面中部横向" />
      </el-form-item>
      <el-form-item label="发现环节">
        <el-select v-model="form.foundStage" clearable placeholder="选择发现环节" style="width: 100%">
          <el-option v-for="f in foundStages" :key="f.value" :label="f.label" :value="f.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="现场说明">
        <el-input v-model="form.siteNote" type="textarea" :rows="2"
                  placeholder="缺陷形态、数量、当时情况…（建立后不可修改，作为历史证据）" />
      </el-form-item>
      <el-form-item label="处置建议">
        <el-input v-model="form.disposeAdvice" type="textarea" :rows="2"
                  placeholder="如 修磨后复检 / 暂停发运待判定 / 直接报废" />
      </el-form-item>
      <el-form-item label="登记人">
        <el-input v-model="form.reporter" placeholder="如 质检-周工" style="width: 220px" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="createVisible = false">取消</el-button>
      <el-button type="warning" :loading="submitting" @click="doCreate">建立隔离并冻结</el-button>
    </template>
  </el-dialog>

  <!-- 详情：快照 + 阻断单 + 时间线 + 受控动作 -->
  <el-drawer v-model="detailVisible" size="620px" :title="'质量隔离记录 · ' + (detail.holdNo || '')">
    <div v-if="detail.id" class="detail-wrap">
      <el-alert v-if="detail.active" :type="detail.status === 'ISOLATED' ? 'warning' : 'primary'"
                :closable="false" show-icon class="stage-alert">
        <template #title>
          当前阶段：<b>{{ detail.statusName }}</b> · {{ detail.severityName }} · {{ detail.defectTypeName }}
          <span class="panel-sub">（第 {{ detail.roundNo }} 轮）</span>
        </template>
        <div class="panel-sub">大板 {{ detail.snapSlabCode }} 发货{{ detail.blockedDeliveries.length ? '已挂起禁运' : '选板已冻结' }}</div>
      </el-alert>
      <el-alert v-else :type="detail.status === 'RELEASED' ? 'success' : 'error'" :closable="false" show-icon
                class="stage-alert">
        <template #title>已收口：{{ detail.statusName }} · {{ fmt(detail.closedAt) }}</template>
        <div class="panel-sub">{{ detail.closedBy }}：{{ detail.closedRemark || '—' }}</div>
      </el-alert>

      <!-- 快照与当前资料对照 -->
      <h4 class="block-title">建立时基础资料快照（历史证据，不随后续资料变化改写）</h4>
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="大板编号">{{ detail.snapSlabCode }}</el-descriptions-item>
        <el-descriptions-item label="所属荒料">{{ detail.snapBlockCode || '—' }}</el-descriptions-item>
        <el-descriptions-item label="当时等级">{{ detail.snapGradeName }}</el-descriptions-item>
        <el-descriptions-item label="当时库位">{{ detail.snapLocation || '—' }}</el-descriptions-item>
        <el-descriptions-item label="当时大板状态" :span="2">{{ detail.snapSlabStatusName }}</el-descriptions-item>
      </el-descriptions>
      <div class="now-strip">
        当前资料：{{ detail.currentSlabCode }} ·
        等级 <b :class="{ changed: detail.currentGrade !== detail.snapGrade }">{{ detail.currentGradeName }}</b> ·
        库位 <b :class="{ changed: detail.currentLocation !== detail.snapLocation }">{{ detail.currentLocation || '—' }}</b> ·
        状态 <b :class="{ changed: detail.currentSlabStatus !== detail.snapSlabStatus }">{{ detail.currentSlabStatusName }}</b>
        <span v-if="detail.slabScrapped" class="scrap-flag">大板已报废不可再流转</span>
      </div>

      <!-- 缺陷登记 -->
      <h4 class="block-title">缺陷登记（最初事实，不可覆盖）</h4>
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="缺陷类型">{{ detail.defectTypeName }}</el-descriptions-item>
        <el-descriptions-item label="严重程度">
          <el-tag size="small" :type="severityType(detail.severity)">{{ detail.severityName }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="影响位置" :span="2">{{ detail.affectPosition || '—' }}</el-descriptions-item>
        <el-descriptions-item label="发现环节">{{ detail.foundStageName || '—' }}</el-descriptions-item>
        <el-descriptions-item label="登记人">{{ detail.reporter || '—' }}</el-descriptions-item>
        <el-descriptions-item label="现场说明" :span="2">{{ detail.siteNote || '—' }}</el-descriptions-item>
        <el-descriptions-item label="处置建议" :span="2">{{ detail.disposeAdvice || '—' }}</el-descriptions-item>
      </el-descriptions>

      <!-- 阻断的发货单 -->
      <h4 class="block-title">当前阻断的待装车发货单</h4>
      <el-table v-if="detail.blockedDeliveries.length" :data="detail.blockedDeliveries" border size="small">
        <el-table-column prop="deliveryNo" label="发货单号" width="150" />
        <el-table-column prop="customer" label="客户" min-width="150" show-overflow-tooltip />
        <el-table-column label="该板金额" width="100">
          <template #default="{ row }">{{ row.itemAmount }}</template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default>
            <el-tag size="small" type="danger">质量挂起·禁运</el-tag>
          </template>
        </el-table-column>
      </el-table>
      <div v-else class="panel-sub" style="padding:6px 2px">
        {{ detail.active ? '当前未挂在待装车发货单上；选板列表也已冻结该板。' : '记录已收口，无阻断单。' }}
      </div>

      <!-- 历轮 -->
      <template v-if="detail.rounds && detail.rounds.length > 1">
        <h4 class="block-title">同板历轮隔离（放行后再发现问题新开一轮，前轮保留）</h4>
        <el-timeline class="round-line">
          <el-timeline-item v-for="r in [...detail.rounds].reverse()" :key="r.id"
                            :timestamp="'第 ' + r.roundNo + ' 轮 · ' + fmt(r.createdAt) + (r.closedAt ? ' → 收口 ' + fmt(r.closedAt) : '')"
                            :type="holdStatusType(r.status)" placement="top">
            <el-link type="primary" @click="jumpTo(r.id)">{{ r.holdNo }}</el-link>
            <el-tag size="small" :type="holdStatusType(r.status)" style="margin-left:8px">{{ r.statusName }}</el-tag>
            <span class="panel-sub" style="margin-left:8px">{{ r.defectTypeName }} · {{ r.severityName }}</span>
          </el-timeline-item>
        </el-timeline>
      </template>

      <!-- 时间线 -->
      <h4 class="block-title">全过程时间线（复检结论只追加，不覆盖历史意见）</h4>
      <el-timeline>
        <el-timeline-item v-for="ev in detail.events" :key="ev.id"
                          :timestamp="fmt(ev.createdAt) + (ev.operator ? ' · ' + ev.operator : '')"
                          :type="eventType(ev.eventType)" placement="top">
          <div class="ev-title">
            <el-tag size="small" :type="eventType(ev.eventType)">{{ ev.eventTypeName }}</el-tag>
            <el-tag v-if="ev.conclusion" size="small" effect="plain" style="margin-left:6px">
              结论：{{ ev.conclusionName }}
            </el-tag>
            <span v-if="ev.fromStatusName" class="panel-sub" style="margin-left:6px">
              {{ ev.fromStatusName }} → {{ ev.toStatusName }}
            </span>
          </div>
          <div v-if="ev.content" class="ev-content">{{ ev.content }}</div>
        </el-timeline-item>
      </el-timeline>

      <!-- 受控动作 -->
      <div v-if="detail.active" class="action-bar">
        <el-button v-if="detail.status === 'ISOLATED'" type="warning" :loading="acting"
                   @click="doSubmitRecheck">送复检</el-button>
        <template v-if="detail.status === 'PENDING_RECHECK'">
          <el-button type="success" :loading="acting" @click="openRecheck('PASS')">复检合格放行</el-button>
          <el-button type="primary" plain :loading="acting" @click="openRecheck('REWORK')">复检意见·返工观察</el-button>
          <el-button type="danger" plain :loading="acting" @click="openRecheck('SCRAP')">复检判定报废</el-button>
        </template>
        <el-button v-if="detail.status === 'ISOLATED'" type="danger" plain :loading="acting"
                   @click="openRecheck('SCRAP', true)">直接判废</el-button>
      </div>
    </div>
  </el-drawer>

  <!-- 送复检 -->
  <el-dialog v-model="recheckSubmitVisible" title="送复检" width="480px" append-to-body>
    <el-form label-width="80px">
      <el-form-item label="隔离记录"><b>{{ detail.holdNo }} · {{ detail.snapSlabCode }}</b></el-form-item>
      <el-form-item label="送检说明">
        <el-input v-model="recheckForm.note" type="textarea" :rows="3"
                  placeholder="复检要点、怀疑方向（追加留痕）" />
      </el-form-item>
      <el-form-item label="操作人">
        <el-input v-model="recheckForm.operator" style="width: 220px" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="recheckSubmitVisible = false">取消</el-button>
      <el-button type="warning" :loading="acting" @click="doSubmitRecheck">确认送复检</el-button>
    </template>
  </el-dialog>

  <!-- 复检结论 -->
  <el-dialog v-model="conclusionVisible" :title="conclusionTitle" width="500px" append-to-body>
    <el-alert v-if="conclusionForm.conclusion === 'SCRAP'" type="error" :closable="false" show-icon
              style="margin-bottom: 12px"
              title="判废后：大板收口为报废不可再流转；系统会把它从【待装车】发货单移除明细并重算总金额。已发运/签收/结款的单只补录、不倒改历史。" />
    <el-alert v-else-if="conclusionForm.conclusion === 'PASS'" type="success" :closable="false" show-icon
              style="margin-bottom: 12px"
              title="放行后自动解除发货挂起，原预定关系与发货单明细原样保留，不做任何删除。" />
    <el-alert v-else type="info" :closable="false" show-icon style="margin-bottom: 12px"
              title="返工观察不改变阶段（仍为待复检），本次意见会追加到时间线，历史意见全部保留。" />
    <el-form label-width="80px">
      <el-form-item label="复检意见">
        <el-input v-model="conclusionForm.opinion" type="textarea" :rows="3"
                  :placeholder="conclusionForm.conclusion === 'PASS' ? '如 返抛后划伤消失，板面合格，同意放行'
                    : conclusionForm.conclusion === 'SCRAP' ? '如 贯穿裂不可修复，判废依据…' : '如 再抛一遍后复检…'" />
      </el-form-item>
      <el-form-item label="复检人">
        <el-input v-model="conclusionForm.operator" style="width: 220px" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="conclusionVisible = false">取消</el-button>
      <el-button :type="conclusionForm.conclusion === 'SCRAP' ? 'danger'
                  : conclusionForm.conclusion === 'PASS' ? 'success' : 'primary'"
                 :loading="acting" @click="doConclusion">确认提交</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { qualityApi, slabApi } from '../api'

const nav = useRouter()

const defectTypes = [
  { value: 'CHIPPED', label: '崩边' },
  { value: 'CRACK', label: '裂纹' },
  { value: 'COLOR_DIFF', label: '色差' },
  { value: 'HOLE', label: '孔洞' },
  { value: 'SCRATCH', label: '划伤' },
  { value: 'OTHER', label: '其他' }
]
const foundStages = [
  { value: 'CUTTING', label: '锯切' },
  { value: 'GRINDING', label: '打磨' },
  { value: 'POLISHING', label: '抛光' },
  { value: 'INSPECTION', label: '成检' },
  { value: 'WAREHOUSE', label: '入库' },
  { value: 'LOADING', label: '装车前' },
  { value: 'FEEDBACK', label: '客户反馈' }
]

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const summary = ref({})
const filters = reactive({ status: '', defectType: '', severity: '', blockCode: '', slabCode: '' })

const createVisible = ref(false)
const submitting = ref(false)
const slabOptions = ref([])
const slabLoading = ref(false)
const form = reactive({
  slabId: null, defectType: 'CHIPPED', severity: 'MAJOR', affectPosition: '',
  foundStage: 'INSPECTION', siteNote: '', disposeAdvice: '', reporter: ''
})

const detailVisible = ref(false)
const detail = ref({})
const acting = ref(false)
const recheckSubmitVisible = ref(false)
const recheckForm = reactive({ note: '', operator: '' })
const conclusionVisible = ref(false)
const conclusionForm = reactive({ conclusion: 'PASS', opinion: '', operator: '', directScrap: false })

const conclusionTitle = ref('复检结论')

function severityType(v) {
  return { MINOR: 'info', MAJOR: 'warning', CRITICAL: 'danger' }[v] || 'info'
}
function holdStatusType(v) {
  return { ISOLATED: 'warning', PENDING_RECHECK: 'primary', RELEASED: 'success', SCRAPPED: 'danger' }[v] || 'info'
}
function eventType(v) {
  return {
    CREATE: 'warning', SUBMIT_RECHECK: 'primary', RECHECK: 'primary',
    RELEASE: 'success', SCRAP: 'danger'
  }[v] || 'info'
}
function fmt(t) {
  if (!t) return '—'
  return String(t).replace('T', ' ').slice(0, 16)
}

function buildParams() {
  const p = { page: page.value, size: size.value, orderBy: 'time', desc: true }
  if (filters.status) p.status = filters.status
  if (filters.defectType) p.defectType = filters.defectType
  if (filters.severity) p.severity = filters.severity
  if (filters.blockCode) p.blockCode = filters.blockCode
  if (filters.slabCode) p.slabCode = filters.slabCode
  return p
}

async function reload() {
  loading.value = true
  try {
    const result = await qualityApi.page(buildParams())
    rows.value = result.rows
    total.value = result.total
    summary.value = await qualityApi.summary()
  } finally {
    loading.value = false
  }
}
function onPage(p) { page.value = p; reload() }
function onSize(s) { size.value = s; page.value = 1; reload() }

// ---------------- 登记 ----------------
function openCreate() {
  Object.assign(form, {
    slabId: null, defectType: 'CHIPPED', severity: 'MAJOR', affectPosition: '',
    foundStage: 'INSPECTION', siteNote: '', disposeAdvice: '', reporter: ''
  })
  slabOptions.value = []
  searchSlabs('')
  createVisible.value = true
}
async function searchSlabs(kw) {
  slabLoading.value = true
  try {
    const result = await slabApi.page({
      keyword: kw || '', size: 30, orderBy: 'code', desc: false
    })
    slabOptions.value = result.rows
  } finally {
    slabLoading.value = false
  }
}
function onPickSlab() {}

async function doCreate() {
  if (!form.slabId) {
    ElMessage.warning('请先选择要隔离的大板')
    return
  }
  submitting.value = true
  try {
    const created = await qualityApi.create({ ...form })
    createVisible.value = false
    ElMessage.success('质量隔离已建立，大板发货已冻结')
    page.value = 1
    await reload()
    openDetail(created)
  } catch (e) {
    // 409：其他人抢先建立了同一大板的隔离，弹冲突提示并打开当前记录
    if (e.isConflict && e.conflict && e.conflict.current && e.conflict.current.id) {
      createVisible.value = false
      await ElMessageBox.alert(
        e.message + '<br/>已为你打开当前生效的隔离记录，无需重复登记。',
        '隔离登记冲突（只允许一条未收口记录）',
        { dangerouslyUseHTMLString: true, type: 'warning', confirmButtonText: '查看当前记录' })
      const fresh = await qualityApi.detail(e.conflict.current.id)
      openDetail(fresh)
      reload()
    }
  } finally {
    submitting.value = false
  }
}

// ---------------- 详情 / 动作 ----------------
async function openDetail(row, focus) {
  detail.value = await qualityApi.detail(row.id)
  detailVisible.value = true
  recheckSubmitVisible.value = false
  conclusionVisible.value = false
  if (focus === 'recheck') {
    if (detail.value.status === 'ISOLATED') {
      recheckForm.note = ''
      recheckForm.operator = ''
      recheckSubmitVisible.value = true
    } else if (detail.value.status === 'PENDING_RECHECK') {
      openRecheck('PASS')
    }
  }
}

async function refreshDetail() {
  if (detail.value.id) {
    detail.value = await qualityApi.detail(detail.value.id)
  }
}

/** 动作条上的「送复检」与弹窗确认共用：弹窗未开则先开，已开则提交 */
async function doSubmitRecheck() {
  if (!recheckSubmitVisible.value) {
    recheckForm.note = ''
    recheckForm.operator = ''
    recheckSubmitVisible.value = true
    return
  }
  acting.value = true
  try {
    detail.value = await qualityApi.submitRecheck(detail.value.id, { ...recheckForm })
    recheckSubmitVisible.value = false
    ElMessage.success('已送复检')
    reload()
  } catch (e) {
    handleConflict(e)
  } finally {
    acting.value = false
  }
}

function openRecheck(conclusion, direct) {
  conclusionForm.conclusion = conclusion
  conclusionForm.opinion = ''
  conclusionForm.operator = ''
  conclusionForm.directScrap = !!direct
  conclusionTitle.value = {
    PASS: '复检合格放行',
    REWORK: '复检意见 · 返工观察',
    SCRAP: direct ? '直接判定报废' : '复检判定报废'
  }[conclusion]
  conclusionVisible.value = true
}

async function doConclusion() {
  acting.value = true
  try {
    let updated
    if (conclusionForm.directScrap) {
      updated = await qualityApi.scrap(detail.value.id, {
        conclusion: 'SCRAP', opinion: conclusionForm.opinion, operator: conclusionForm.operator
      })
    } else {
      updated = await qualityApi.recheck(detail.value.id, {
        conclusion: conclusionForm.conclusion,
        opinion: conclusionForm.opinion,
        operator: conclusionForm.operator
      })
    }
    detail.value = updated
    conclusionVisible.value = false
    ElMessage.success(
      conclusionForm.conclusion === 'PASS' ? '已放行，发货挂起自动解除'
        : conclusionForm.conclusion === 'SCRAP' ? '已报废收口，待装车单明细已移除并重算'
        : '复检意见已追加')
    reload()
  } catch (e) {
    handleConflict(e)
  } finally {
    acting.value = false
  }
}

/** 并发复检 / 重复点击：只有一个生效，另一方拿到最新状态 */
async function handleConflict(e) {
  if (e.isConflict && e.conflict && e.conflict.current && e.conflict.current.id) {
    await ElMessageBox.alert(
      e.message + '<br/>页面已刷新到该记录的最新状态，请在最新状态上操作。',
      '操作冲突（状态已被其他人更新）',
      { dangerouslyUseHTMLString: true, type: 'warning', confirmButtonText: '刷新到最新' })
    detail.value = await qualityApi.detail(e.conflict.current.id)
    reload()
  }
}

function jumpTo(id) {
  qualityApi.detail(id).then((d) => { detail.value = d })
}

onMounted(reload)
</script>

<style scoped>
.block-cell {
  border-left-color: var(--mill-danger);
}
.block-cell b {
  color: var(--mill-danger);
}
.block-tag {
  margin: 2px 6px 2px 0;
}
.detail-wrap {
  padding-bottom: 30px;
}
.stage-alert {
  margin-bottom: 14px;
}
.block-title {
  margin: 18px 0 8px;
  font-size: 13px;
  color: var(--mill-primary);
  border-left: 3px solid var(--mill-primary);
  padding-left: 8px;
}
.now-strip {
  margin-top: 8px;
  font-size: 12px;
  color: var(--mill-text-sub);
  background: var(--mill-primary-soft);
  border-radius: 4px;
  padding: 6px 10px;
}
.now-strip .changed {
  color: var(--mill-warn);
}
.scrap-flag {
  margin-left: 8px;
  color: #fff;
  background: var(--mill-danger);
  border-radius: 3px;
  padding: 0 6px;
}
.ev-title {
  margin-bottom: 2px;
}
.ev-content {
  font-size: 13px;
  color: var(--mill-text);
  margin-top: 2px;
}
.action-bar {
  position: sticky;
  bottom: 0;
  background: #fff;
  padding: 12px 0;
  border-top: 1px solid var(--mill-border);
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}
.round-line {
  padding-left: 4px;
}
</style>
