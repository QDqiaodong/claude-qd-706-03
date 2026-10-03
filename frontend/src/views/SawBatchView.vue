<template>
  <section class="panel">
    <div class="panel-head">
      <h3>锯切批次 · 交接与产出谱系</h3>
      <span class="panel-sub">开锯冻结荒料/工单/锯切方式/排板顺序 · 逐片扫码登记 · 差异待核阻断收口 · 收口按合格实产推进工单</span>
      <div class="panel-extra">
        <el-select v-model="filters.status" clearable placeholder="批次状态" style="width: 130px" @change="reload">
          <el-option label="开锯交接中" value="OPEN" />
          <el-option label="差异待核" value="PENDING_RECONCILE" />
          <el-option label="可收口" value="READY" />
          <el-option label="已收口" value="CLOSED" />
        </el-select>
        <el-select v-model="filters.team" clearable placeholder="班组" style="width: 100px" @change="reload">
          <el-option label="甲班" value="甲班" />
          <el-option label="乙班" value="乙班" />
          <el-option label="丙班" value="丙班" />
        </el-select>
        <el-input v-model="filters.keyword" clearable placeholder="批次号 / 工单号" style="width: 150px"
                  @keyup.enter="reload" @clear="reload" />
        <el-button @click="reload">刷新</el-button>
        <el-button type="primary" @click="openCreate">开锯建批</el-button>
      </div>
    </div>

    <div class="summary-bar">
      <div class="summary-cell"><span>开锯交接中</span><b>{{ countStatus('OPEN') }}</b></div>
      <div class="summary-cell block-cell"><span>差异待核</span><b>{{ countStatus('PENDING_RECONCILE') }}</b> 批阻断</div>
      <div class="summary-cell"><span>可收口</span><b>{{ countStatus('READY') }}</b></div>
      <div class="summary-cell"><span>已收口</span><b>{{ countStatus('CLOSED') }}</b></div>
    </div>

    <el-table :data="rows" border stripe size="small" v-loading="loading"
              :row-class-name="rowClass">
      <el-table-column prop="batchNo" label="批次号" width="130" />
      <el-table-column label="工单 / 荒料" width="190">
        <template #default="{ row }">
          <div><b>{{ row.snapOrderNo }}</b></div>
          <div class="panel-sub">{{ row.snapBlockCode }} · 计划 {{ row.planCount }} 片</div>
        </template>
      </el-table-column>
      <el-table-column prop="sawTypeName" label="锯切方式" width="90" />
      <el-table-column prop="team" label="班组" width="70" />
      <el-table-column label="实产（成品/余料/锯耗）" width="170">
        <template #default="{ row }">
          <b>{{ row.activeFinishedCount }}</b> / {{ row.activeRemnantCount }} / {{ row.activeSawLossCount }}
          <span class="panel-sub">（合格 {{ row.activeQualifiedCount }}）</span>
        </template>
      </el-table-column>
      <el-table-column label="差异" width="90" align="center">
        <template #default="{ row }">
          <el-badge v-if="row.openDiffCount > 0" :value="row.openDiffCount" type="danger">
            <el-tag size="small" type="danger">待核</el-tag>
          </el-badge>
          <el-tag v-else size="small" type="success">已核清</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="104">
        <template #default="{ row }">
          <el-tag size="small" :type="statusType(row.status)">{{ row.statusName }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="openedAt" label="开锯时间" width="158">
        <template #default="{ row }">{{ fmt(row.openedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">批次谱系</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination background layout="total, sizes, prev, pager, next"
                     :total="total" :current-page="page" :page-size="size"
                     :page-sizes="[10, 20, 50]" @current-change="onPage" @size-change="onSize" />
    </div>

    <!-- 开锯建批 -->
    <el-dialog v-model="createVisible" title="开锯建批（冻结排板顺序）" width="560px">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px"
                title="建批瞬间冻结工单的荒料、锯切方式、班组、计划板数与当前排板顺序；之后排板重算不改写本批。首个批次会固化工单当时完成数为基线。" />
      <el-form :model="form" label-width="96px">
        <el-form-item label="加工工单" required>
          <el-select v-model="form.orderId" filterable placeholder="选择已排产工单" style="width: 100%"
                     @change="onPickOrder">
            <el-option v-for="o in orders" :key="o.id"
                       :label="o.orderNo + ' · ' + o.blockCode + ' · ' + o.sawTypeName + ' · 计划' + o.planCount"
                       :value="o.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="覆盖顺序">
          <el-select v-model="form.seqNos" multiple collapse-tags collapse-tags-tooltip
                     :placeholder="pickedSeqs.length ? '选择顺序号，留空=全部' : '该荒料需先排板'"
                     style="width: 100%">
            <el-option v-for="s in pickedSeqs" :key="s" :label="'第 ' + s + ' 序'" :value="s" />
          </el-select>
          <div class="panel-sub">同一荒料可分多批开锯（如先开 1~4 序，再开 5~8 序），留空冻结全部顺序。</div>
        </el-form-item>
        <el-form-item label="开锯操作人">
          <el-input v-model="form.openedBy" placeholder="如 乙班-吴锯工" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="doCreate">建立批次</el-button>
      </template>
    </el-dialog>

    <!-- 批次详情 / 谱系 -->
    <el-drawer v-model="detailVisible" size="72%" :title="detailTitle">
      <div v-if="detail.id" class="batch-detail">
        <!-- 冻结头 -->
        <el-descriptions :column="4" border size="small">
          <el-descriptions-item label="批次号">{{ detail.batchNo }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag size="small" :type="statusType(detail.status)">{{ detail.statusName }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="工单">{{ detail.snapOrderNo }}</el-descriptions-item>
          <el-descriptions-item label="荒料">{{ detail.snapBlockCode }}</el-descriptions-item>
          <el-descriptions-item label="锯切方式">{{ detail.sawTypeName }}（冻结）</el-descriptions-item>
          <el-descriptions-item label="班组">{{ detail.team || '—' }}（冻结）</el-descriptions-item>
          <el-descriptions-item label="计划板数">{{ detail.planCount }}（冻结）</el-descriptions-item>
          <el-descriptions-item label="开锯人/时间">
            {{ detail.openedBy || '—' }} · {{ fmt(detail.openedAt) }}
          </el-descriptions-item>
          <el-descriptions-item label="交接人/时间" :span="2">
            {{ detail.handoverBy || '交接中' }} · {{ detail.handoverAt ? fmt(detail.handoverAt) : '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="收口人/时间" :span="2">
            {{ detail.closedBy || '—' }} · {{ detail.closedAt ? fmt(detail.closedAt) : '—' }}
          </el-descriptions-item>
        </el-descriptions>

        <!-- 操作条 -->
        <div class="action-bar">
          <el-button type="primary" :disabled="detail.status === 'CLOSED'"
                     @click="openRegister(detail)">逐片登记</el-button>
          <el-button type="warning" :disabled="detail.status !== 'OPEN'"
                     @click="doHandover(detail)">完成交接并比对</el-button>
          <el-button type="success" :disabled="detail.status !== 'READY'"
                     @click="doClose(detail)">收口固化谱系</el-button>
          <el-tag v-if="detail.status === 'PENDING_RECONCILE'" type="danger" size="large">
            {{ detail.openDiffCount }} 条差异待核：核清前不能收口、不计工单完成数
          </el-tag>
          <el-tag v-if="detail.status === 'READY'" type="success" size="large">差异已全部核清，可以收口</el-tag>
        </div>

        <!-- 计划 vs 实产（冻结顺序 + 逐片） -->
        <h4>冻结排板顺序 × 逐片实产</h4>
        <el-table :data="planRows" border size="small">
          <el-table-column label="顺序" width="70" align="center">
            <template #default="{ row }">第{{ row.seqNo }}序</template>
          </el-table-column>
          <el-table-column label="计划尺寸（冻结）" width="150">
            <template #default="{ row }">{{ row.planWidth }} × {{ row.planHeight }} cm</template>
          </el-table-column>
          <el-table-column label="实产归属">
            <template #default="{ row }">
              <template v-if="row.slabCodes">
                <el-tag v-for="c in row.slabCodes.split('、')" :key="c" size="small"
                        :type="row.outputCount > 1 ? 'danger' : 'success'" class="block-tag">
                  {{ c }}
                </el-tag>
                <el-tag v-if="row.outputCount > 1" size="small" type="danger">重复号</el-tag>
              </template>
              <el-tag v-else size="small" type="info">缺号</el-tag>
            </template>
          </el-table-column>
        </el-table>

        <!-- 逐片产出 -->
        <h4>逐片产出（成品 / 可返切余料 / 锯耗）</h4>
        <el-table :data="detail.outputs" border size="small">
          <el-table-column label="顺序" width="64" align="center">
            <template #default="{ row }">{{ row.seqNo || '—' }}</template>
          </el-table-column>
          <el-table-column label="类型" width="104">
            <template #default="{ row }">
              <el-tag size="small" :type="outputType(row.outputType)">{{ row.outputTypeName }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="slabCode" label="大板编号" width="120">
            <template #default="{ row }">{{ row.slabCode || '—' }}</template>
          </el-table-column>
          <el-table-column label="实际尺寸" width="130">
            <template #default="{ row }">
              {{ row.actualLength ? row.actualLength + '×' + row.actualWidth + 'cm' : '—' }}
            </template>
          </el-table-column>
          <el-table-column label="等级" width="74">
            <template #default="{ row }">{{ row.gradeName || '—' }}</template>
          </el-table-column>
          <el-table-column prop="sourceSlabCode" label="返切来源" width="120">
            <template #default="{ row }">{{ row.sourceSlabCode || '—' }}</template>
          </el-table-column>
          <el-table-column prop="lossNote" label="锯耗说明" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ row.lossNote || '' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ row.statusName }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="operator" label="登记人" width="110" />
          <el-table-column label="登记时间" width="158">
            <template #default="{ row }">{{ fmt(row.registeredAt) }}</template>
          </el-table-column>
        </el-table>

        <!-- 差异待核 -->
        <h4>差异待核清单（计划 vs 实产）</h4>
        <el-table :data="detail.diffs" border size="small" :row-class-name="diffRowClass">
          <el-table-column label="类型" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="diffType(row.diffType)">{{ row.diffTypeName }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="顺序" width="64" align="center">
            <template #default="{ row }">{{ row.seqNo || '—' }}</template>
          </el-table-column>
          <el-table-column prop="outputSlabCode" label="大板" width="110">
            <template #default="{ row }">{{ row.outputSlabCode || '—' }}</template>
          </el-table-column>
          <el-table-column prop="detail" label="差异说明（计划/实值）" min-width="240" show-overflow-tooltip />
          <el-table-column label="核清" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'OPEN' ? 'danger' : 'success'">{{ row.statusName }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="reason" label="核清原因" min-width="200" show-overflow-tooltip />
          <el-table-column label="操作" width="220" fixed="right">
            <template #default="{ row }">
              <template v-if="row.status === 'OPEN' && detail.status !== 'CLOSED'">
                <el-button link type="success" @click="openResolve(row, 'CONFIRM')">确认实际</el-button>
                <el-button link type="warning" @click="openResolve(row, 'CORRECT_ASSIGN')">更正归属</el-button>
                <el-button link type="danger" @click="openResolve(row, 'CORRECT_VOID')">作废</el-button>
              </template>
              <span v-else class="panel-sub">{{ row.resolvedBy }} · {{ fmt(row.resolvedAt) }}</span>
            </template>
          </el-table-column>
        </el-table>

        <!-- 谱系留痕 -->
        <h4>谱系留痕（建批 / 交接 / 登记 / 更正 / 收口，只追加）</h4>
        <el-timeline>
          <el-timeline-item v-for="a in detail.audits" :key="a.id"
                            :timestamp="(a.operator || '') + ' · ' + fmt(a.createdAt)"
                            :type="auditColor(a.eventType)" placement="top">
            <b>{{ a.eventTypeName }}</b>
            <div v-if="a.fromContent" class="audit-from">变更前：{{ a.fromContent }}</div>
            <div v-if="a.toContent">变更后：{{ a.toContent }}</div>
            <div v-if="a.reason" class="audit-reason">原因：{{ a.reason }}</div>
          </el-timeline-item>
        </el-timeline>
      </div>
    </el-drawer>

    <!-- 逐片登记 -->
    <el-dialog v-model="regVisible" title="逐片产出登记" width="600px">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 10px"
                title="断网重试 / 重复扫码：相同扫码幂等键只生成首次产出；大板已归属其他批次会被拒绝并显示当前归属，不会静默覆盖。" />
      <el-form :model="reg" label-width="104px">
        <el-form-item label="产出类型" required>
          <el-radio-group v-model="reg.outputType">
            <el-radio-button value="FINISHED">成品</el-radio-button>
            <el-radio-button value="REMNANT">可返切余料</el-radio-button>
            <el-radio-button value="SAW_LOSS">锯耗</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="对应顺序号">
          <el-select v-model="reg.seqNo" clearable style="width: 200px"
                     :placeholder="reg.outputType === 'REMNANT' ? '余料可不占顺序' : '选择冻结顺序'">
            <el-option v-for="i in (detail.items || [])" :key="i.id" :label="'第 ' + i.seqNo + ' 序'" :value="i.seqNo" />
          </el-select>
        </el-form-item>
        <template v-if="reg.outputType !== 'SAW_LOSS'">
          <el-form-item label="大板">
            <el-radio-group v-model="reg.pickMode">
              <el-radio value="new">新切大板</el-radio>
              <el-radio value="exist">选用已有大板</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item v-if="reg.pickMode === 'new'" label="大板编号" required>
            <el-input v-model="reg.slabCode" placeholder="如 SLB-0206" style="width: 220px" />
          </el-form-item>
          <el-form-item v-else label="选择大板" required>
            <el-select v-model="reg.slabId" filterable style="width: 360px"
                       placeholder="仅列出本荒料大板">
              <el-option v-for="s in blockSlabs" :key="s.id"
                         :label="s.slabCode + ' · ' + s.lengthCm + '×' + s.widthCm + ' · ' + s.statusName"
                         :value="s.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="实际尺寸" required>
            <el-input-number v-model="reg.lengthCm" :min="1" controls-position="right" /> 长 ×
            <el-input-number v-model="reg.widthCm" :min="1" controls-position="right" /> 宽 cm
          </el-form-item>
          <el-form-item label="厚度 cm">
            <el-input-number v-model="reg.thicknessCm" :min="0.1" :step="0.1" controls-position="right" />
          </el-form-item>
          <el-form-item label="等级">
            <el-select v-model="reg.grade" style="width: 140px">
              <el-option label="A 级" value="A" /><el-option label="B 级" value="B" />
              <el-option label="C 级" value="C" /><el-option label="次品" value="DEFECT" />
            </el-select>
          </el-form-item>
          <el-form-item label="表面处理">
            <el-select v-model="reg.finish" style="width: 140px">
              <el-option label="光面" value="POLISHED" /><el-option label="亚光" value="HONED" />
              <el-option label="荔枝面" value="LITCHI" /><el-option label="火烧面" value="FLAMED" />
              <el-option label="皮革面" value="LEATHER" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="reg.outputType === 'REMNANT'" label="返切来源大板">
            <el-select v-model="reg.sourceSlabId" filterable clearable style="width: 360px">
              <el-option v-for="s in blockSlabs" :key="s.id"
                         :label="s.slabCode + ' · ' + s.lengthCm + '×' + s.widthCm" :value="s.id" />
            </el-select>
          </el-form-item>
        </template>
        <el-form-item v-else label="锯耗说明">
          <el-input v-model="reg.lossNote" type="textarea" :rows="2"
                    placeholder="不填将作为「未解释锯耗」进入差异待核，必须说明才能收口" />
        </el-form-item>
        <el-form-item label="扫码幂等键" required>
          <el-input v-model="reg.clientToken" style="width: 300px" placeholder="现场设备生成，重试保持不变">
            <template #append>
              <el-button @click="genToken">生成</el-button>
            </template>
          </el-input>
        </el-form-item>
        <el-form-item label="操作人">
          <el-input v-model="reg.operator" style="width: 220px" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="regVisible = false">取消</el-button>
        <el-button type="primary" @click="doRegister">登记产出</el-button>
      </template>
    </el-dialog>

    <!-- 差异核清 -->
    <el-dialog v-model="resolveVisible" :title="resolveTitle" width="560px">
      <el-alert :type="currentDiff && currentDiff.diffType === 'SIZE_TOLERANCE' ? 'warning' : 'info'"
                :closable="false" show-icon style="margin-bottom: 10px"
                :title="currentDiff ? currentDiff.detail : ''" />
      <el-form :model="resolve" label-width="92px">
        <el-form-item v-if="resolve.action === 'CORRECT_ASSIGN'" label="目标批次" required>
          <el-select v-model="resolve.targetBatchId" style="width: 100%"
                     placeholder="同一荒料的另一个未收口批次">
            <el-option v-for="b in resolveCandidates.length ? resolveCandidates : siblingBatches"
                       :key="b.id" :label="b.batchNo + ' · ' + b.statusName" :value="b.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="resolve.action === 'CORRECT_ASSIGN'" label="更正后顺序">
          <el-input-number v-model="resolve.targetSeqNo" :min="1" controls-position="right" />
          <span class="panel-sub">留空保持原顺序号</span>
        </el-form-item>
        <el-form-item label="核清原因" required>
          <el-input v-model="resolve.reason" type="textarea" :rows="3"
                    :placeholder="resolve.action === 'CONFIRM'
                      ? '确认按实际结果记账的原因（如：端面暗裂避让下刀，短 7cm 不影响排版）'
                      : '更正/作废原因（变更前后会写留痕，已被后续批次引用的产出不能作废）'" />
        </el-form-item>
        <el-form-item label="负责人">
          <el-input v-model="resolve.operator" style="width: 220px" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resolveVisible = false">取消</el-button>
        <el-button :type="resolve.action === 'CORRECT_VOID' ? 'danger' : 'primary'" @click="doResolve">
          提交核清
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { orderApi, sawApi, slabApi } from '../api'

const route = useRoute()

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const filters = ref({ status: '', team: '', keyword: '' })

const orders = ref([])
const createVisible = ref(false)
const form = ref({ orderId: null, seqNos: [], openedBy: '', remark: '' })
const pickedSeqs = ref([])
const pickedBlockId = ref(null)

const detailVisible = ref(false)
const detail = ref({})
const detailId = ref(null)

const regVisible = ref(false)
const reg = ref({})
const blockSlabs = ref([])

const resolveVisible = ref(false)
const currentDiff = ref(null)
const resolve = ref({})

const detailTitle = computed(() => detail.value.id
  ? `批次谱系 · ${detail.value.batchNo}（${detail.value.statusName}）`
  : '批次谱系')

const planRows = computed(() => detail.value.items || [])
const siblingBatches = computed(() => rows.value.filter((b) =>
  b.blockId === detail.value.blockId && b.status !== 'CLOSED'))

function statusType(s) {
  return { OPEN: 'warning', PENDING_RECONCILE: 'danger', READY: 'success', CLOSED: 'info' }[s] || 'info'
}
function rowClass({ row }) {
  return row.openDiffCount > 0 ? 'row-diff' : ''
}
function diffRowClass({ row }) {
  return row.status === 'OPEN' ? 'row-diff' : ''
}
function countStatus(s) {
  return rows.value.filter((r) => r.status === s).length
}
function outputType(t) {
  return { FINISHED: 'success', REMNANT: 'warning', SAW_LOSS: 'info' }[t] || ''
}
function diffType(t) {
  return { MISSING_SEQ: 'danger', DUP_SEQ: 'danger', SIZE_TOLERANCE: 'warning',
    COUNT_MISMATCH: 'danger', UNEXPLAINED_SAW_LOSS: 'warning' }[t] || 'info'
}
function auditColor(t) {
  return { CLOSE_BATCH: 'success', CORRECT_OUTPUT: 'warning', VOID_OUTPUT: 'danger',
    CONFIRM_DIFF: 'primary', SUBMIT_HANDOVER: 'primary' }[t] || ''
}
function fmt(v) {
  if (!v) return '—'
  return String(v).replace('T', ' ').slice(0, 16)
}

async function reload() {
  loading.value = true
  try {
    const params = { page: page.value, size: size.value, orderBy: 'time', desc: true }
    if (filters.value.status) params.status = filters.value.status
    if (filters.value.team) params.team = filters.value.team
    if (filters.value.keyword) params.keyword = filters.value.keyword
    const r = await sawApi.page(params)
    rows.value = r.rows
    total.value = r.total
  } finally {
    loading.value = false
  }
}
function onPage(p) { page.value = p; reload() }
function onSize(s) { size.value = s; page.value = 1; reload() }

async function openCreate() {
  orders.value = await orderApi.list()
  form.value = { orderId: null, seqNos: [], openedBy: '', remark: '' }
  pickedSeqs.value = []
  pickedBlockId.value = null
  createVisible.value = true
}

async function onPickOrder(id) {
  const o = orders.value.find((x) => x.id === id)
  pickedBlockId.value = o ? o.blockId : null
  form.value.seqNos = []
  pickedSeqs.value = []
  if (!id) return
  // 用该工单已有批次之外的排板顺序：直接读排板方案的 seq
  try {
    const plan = await import('../api').then((m) => m.layoutApi.plan(pickedBlockId.value))
    pickedSeqs.value = (plan.items || []).map((i) => i.seqNo)
  } catch (e) {
    pickedSeqs.value = []
  }
}

async function doCreate() {
  if (!form.value.orderId) return ElMessage.warning('请选择加工工单')
  await sawApi.create(form.value)
  createVisible.value = false
  ElMessage.success('锯切批次已建立，排板顺序已冻结')
  reload()
}

async function openDetail(row) {
  detailId.value = row.id
  await loadDetail()
  detailVisible.value = true
}
async function loadDetail() {
  detail.value = await sawApi.detail(detailId.value)
}

async function openRegister() {
  reg.value = {
    outputType: 'FINISHED', seqNo: null, pickMode: 'new', slabCode: '', slabId: null,
    lengthCm: null, widthCm: null, thicknessCm: 2.0, grade: 'A', finish: 'HONED',
    sourceSlabId: null, lossNote: '', clientToken: genTokenValue(), operator: ''
  }
  try {
    blockSlabs.value = await slabApi.byBlock(detail.value.blockId)
  } catch (e) {
    blockSlabs.value = []
  }
  regVisible.value = true
}
function genTokenValue() {
  return 'cli-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8)
}
function genToken() {
  reg.value.clientToken = genTokenValue()
}

async function doRegister() {
  const payload = { ...reg.value }
  delete payload.pickMode
  try {
    const r = await sawApi.register(detail.value.id, payload)
    regVisible.value = false
    if (r.duplicated) {
      ElMessage.warning(r.message || '该扫码已登记过，返回首次产出（未重复生成）')
    } else {
      ElMessage.success(r.message || '产出已登记')
    }
    await loadDetail()
    await reload()
  } catch (e) {
    // 跨批次占用：409 带当前归属
    if (e.isConflict) await handleOccupied(e)
  }
}

async function handleOccupied(e) {
  const c = e.conflict && e.conflict.current ? e.conflict.current : {}
  await ElMessageBox.alert(
    `该大板已先登记在批次 ${c.batchNo || ''}（第 ${c.seqNo ?? '—'} 序，登记人 ${c.operator || '—'}，时间 ${fmt(c.registeredAt)}）。`
    + '先到记录已保留，不能静默覆盖。如确属扫错批次，请由负责人在差异清单做「更正归属」。',
    '跨批次占用：已显示当前归属', { type: 'warning', confirmButtonText: '知道了' })
  await loadDetail()
}

async function doHandover(row) {
  try {
    const { value: operator } = await ElMessageBox.prompt('交接操作人', '完成交接并比对计划/实产', {
      confirmButtonText: '提交交接', cancelButtonText: '取消', inputPlaceholder: '如 甲班-周交接'
    })
    const v = await sawApi.handover(row.id, { handoverBy: operator })
    ElMessage.success(v.openDiffCount > 0
      ? `交接完成，${v.openDiffCount} 条差异进入待核（不收口、不计完成数）`
      : '交接完成，无差异，可收口')
    await loadDetail(); await reload()
  } catch (e) {
    if (e === 'cancel' || (e && e.message === 'cancel')) return
    throw e
  }
}

async function doClose(row) {
  try {
    const { value: operator } = await ElMessageBox.prompt(
      '收口将一次性固化成品/余料/锯耗/合格实产，并按合格实产推进工单完成数。重复收口只会形成一个结果。',
      '收口确认', { confirmButtonText: '确认收口', cancelButtonText: '取消', inputPlaceholder: '收口负责人' })
    await sawApi.close(row.id, { closedBy: operator })
    ElMessage.success('批次已收口，谱系固化，工单完成数已按合格实产推进')
    await loadDetail(); await reload()
  } catch (e) {
    if (e === 'cancel' || (e && e.message === 'cancel')) return
    if (e.isConflict && e.conflict && e.conflict.current) {
      const c = e.conflict.current
      await ElMessageBox.alert(`批次已由 ${c.closedBy || '—'} 于 ${fmt(c.closedAt)} 收口，`
        + `合格实产 ${c.qualifiedCount} 片。重复收口未形成第二个结果。`,
        '并发收口冲突', { type: 'warning' })
      await loadDetail(); await reload()
      return
    }
    throw e
  }
}

const resolveTitle = computed(() => ({
  CONFIRM: '确认实际结果（差异照实接受）',
  CORRECT_ASSIGN: '更正产出归属（转到目标批次）',
  CORRECT_VOID: '作废错误产出（记录保留）'
}[resolve.value.action] || '差异核清'))

async function openResolve(diff, action) {
  currentDiff.value = diff
  // 同荒料全部未收口批次（直接查，避免分页/深链时下拉缺批次）
  let sameBlock = rows.value.filter((b) => b.blockId === detail.value.blockId)
  try {
    const r = await sawApi.page({ blockId: detail.value.blockId, page: 1, size: 100 })
    sameBlock = (r.rows || []).filter((b) => b.status !== 'CLOSED')
  } catch (e) { /* 用列表兜底 */ }
  // 归属更正默认转入当前查看的批次（实物被别的批次误扫时，目标往往就是本批）
  const targetDefault = sameBlock.find((b) => b.id === detail.value.id) || sameBlock[0] || null
  resolve.value = {
    action, reason: '', operator: '',
    targetBatchId: targetDefault ? targetDefault.id : null,
    targetSeqNo: null, outputId: diff.outputId
  }
  // 临时写入同荒料批次供下拉（组件 computed 优先用列表，这里直接覆盖一次）
  resolveCandidates.value = sameBlock
  resolveVisible.value = true
}

const resolveCandidates = ref([])

async function doResolve() {
  if (!resolve.value.reason) return ElMessage.warning('必须填写核清原因')
  try {
    const v = await sawApi.resolveDiff(currentDiff.value.id, {
      action: resolve.value.action, reason: resolve.value.reason, operator: resolve.value.operator,
      targetBatchId: resolve.value.targetBatchId, targetSeqNo: resolve.value.targetSeqNo,
      outputId: resolve.value.outputId
    })
    ElMessage.success(`差异已核清，剩余待核 ${v.openDiffCount} 条`)
    resolveVisible.value = false
    await loadDetail(); await reload()
  } catch (e) {
    if (e && e.message) ElMessage.error(e.message)
  }
}

onMounted(async () => {
  await reload()
  if (route.query.batch) {
    detailId.value = Number(route.query.batch)
    await loadDetail()
    detailVisible.value = true
  }
})
</script>

<style scoped>
.summary-bar {
  display: flex;
  gap: 12px;
  margin: 10px 0;
}
.summary-cell {
  flex: 1;
  background: var(--mill-bg, #f6f8f9);
  border: 1px solid #e3e8ea;
  border-radius: 6px;
  padding: 8px 12px;
  font-size: 13px;
}
.summary-cell b {
  font-size: 20px;
  margin-left: 8px;
}
.block-cell { border-color: #f0c0c0; }
.action-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 12px 0;
}
.batch-detail h4 {
  margin: 16px 0 8px;
  border-left: 3px solid var(--el-color-primary);
  padding-left: 8px;
}
.block-tag { margin-right: 4px; }
.audit-from { color: #90a4ae; font-size: 12px; }
.audit-reason { color: #e6a23c; font-size: 12px; }
:deep(.row-diff) { background-color: #fff7f5 !important; }
</style>
