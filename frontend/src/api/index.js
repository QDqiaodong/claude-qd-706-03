import axios from 'axios'
import { ElMessage } from 'element-plus'

/**
 * 统一走 nginx 反代到后端 /api：
 *   成功 → 后端包成 { ok:true, message, data }，这里直接把 data 交给页面
 *   失败 → 后端包成 { ok:false, message:"中文业务提示" }，这里统一弹提示并 reject
 */
const http = axios.create({ baseURL: '/api', timeout: 15000 })

http.interceptors.response.use(
  (res) => {
    const body = res.data
    if (body && typeof body === 'object' && Object.prototype.hasOwnProperty.call(body, 'ok')) {
      if (body.ok) return body.data
      ElMessage.error(body.message || '操作失败')
      return Promise.reject(new Error(body.message || '操作失败'))
    }
    return body
  },
  (err) => {
    const body = err.response && err.response.data
    const message = (body && body.message) || err.message || '网络异常，请稍后再试'
    const e = new Error(message)
    // 409 并发冲突：把后端回带的当前最新数据交给调用方（页面弹冲突提示并刷新），不弹通用错误
    if (err.response && err.response.status === 409 && body && body.ok === false) {
      e.conflict = body.data || {}
      e.isConflict = true
      return Promise.reject(e)
    }
    ElMessage.error(message)
    return Promise.reject(e)
  }
)

/** 全站：总览计数器 + 左侧荒料树 */
export const millApi = {
  overview: () => http.get('/mill/overview'),
  tree: (params) => http.get('/mill/tree', { params: params || {} })
}

/** 模块一：荒料 */
export const blockApi = {
  page: (params) => http.get('/blocks', { params }),
  list: () => http.get('/blocks/list'),
  summary: (params) => http.get('/blocks/summary', { params }),
  detail: (id) => http.get(`/blocks/${id}`),
  create: (data) => http.post('/blocks', data),
  setStatus: (id, status) => http.post(`/blocks/${id}/status`, { status })
}

/** 模块二：大板 */
export const slabApi = {
  page: (params) => http.get('/slabs', { params }),
  shippable: (params) => http.get('/slabs/shippable', { params }),
  summary: (params) => http.get('/slabs/summary', { params }),
  detail: (id) => http.get(`/slabs/${id}`),
  byBlock: (blockId) => http.get('/slabs/by-block', { params: { blockId } }),
  create: (data) => http.post('/slabs', data),
  setStatus: (id, status) => http.post(`/slabs/${id}/status`, { status })
}

/** 模块三：加工工单 */
export const orderApi = {
  page: (params) => http.get('/cutting-orders', { params }),
  list: () => http.get('/cutting-orders/list'),
  create: (data) => http.post('/cutting-orders', data),
  start: (id) => http.post(`/cutting-orders/${id}/start`),
  progress: (id, delta) => http.post(`/cutting-orders/${id}/progress`, { delta }),
  finish: (id) => http.post(`/cutting-orders/${id}/finish`),
  settle: (id) => http.post(`/cutting-orders/${id}/settle`)
}

/** 模块四：排板方案（首页 SVG 切割排版图） */
export const layoutApi = {
  plan: (blockId) => http.get('/layouts', { params: { blockId } }),
  relayout: (blockId, slabIds) => http.post(`/layouts/${blockId}/relayout`, slabIds ? { slabIds } : {})
}

/** 模块五：出库发货 */
export const deliveryApi = {
  page: (params) => http.get('/deliveries', { params }),
  summary: (params) => http.get('/deliveries/summary', { params }),
  detail: (id) => http.get(`/deliveries/${id}`),
  items: (id) => http.get(`/deliveries/${id}/items`),
  create: (data) => http.post('/deliveries', data),
  addItem: (id, data) => http.post(`/deliveries/${id}/items`, data),
  setStatus: (id, status) => http.post(`/deliveries/${id}/status`, { status })
}

/** 模块六/七：大板质量隔离与复检放行 */
export const qualityApi = {
  page: (params) => http.get('/quality-holds', { params }),
  summary: () => http.get('/quality-holds/summary'),
  detail: (id) => http.get(`/quality-holds/${id}`),
  activeBySlab: (slabId) => http.get('/quality-holds/active-by-slab', { params: { slabId } }),
  create: (data) => http.post('/quality-holds', data),
  submitRecheck: (id, data) => http.post(`/quality-holds/${id}/submit-recheck`, data),
  recheck: (id, data) => http.post(`/quality-holds/${id}/recheck`, data),
  scrap: (id, data) => http.post(`/quality-holds/${id}/scrap`, data)
}

/** 模块八~十二：锯切批次交接与产出谱系 */
export const sawApi = {
  page: (params) => http.get('/saw-batches', { params }),
  byOrder: (orderId) => http.get('/saw-batches/by-order', { params: { orderId } }),
  detail: (id) => http.get(`/saw-batches/${id}`),
  traceBySlab: (slabId) => http.get('/saw-batches/trace-by-slab', { params: { slabId } }),
  create: (data) => http.post('/saw-batches', data),
  register: (id, data) => http.post(`/saw-batches/${id}/outputs`, data),
  handover: (id, data) => http.post(`/saw-batches/${id}/handover`, data),
  resolveDiff: (diffId, data) => http.post(`/saw-batches/discrepancies/${diffId}/resolve`, data),
  close: (id, data) => http.post(`/saw-batches/${id}/close`, data)
}
