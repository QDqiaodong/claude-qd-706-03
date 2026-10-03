import { createRouter, createWebHistory } from 'vue-router'

/**
 * 导航形态：左侧「荒料批次 → 大板」树 + 顶部三个筛选（石种 / 等级 / 状态），
 * 首页是切割排版图（纯 SVG 手绘），其余四个模块各占一页。
 */
export const navItems = [
  {
    key: 'layout',
    path: '/layout',
    icon: '▦',
    label: '切割排版图',
    hint: 'SVG 手绘荒料排布 · 按等级着色 · 损耗率'
  },
  {
    key: 'blocks',
    path: '/blocks',
    icon: '▣',
    label: '荒料台账',
    hint: '矿口产地·石种·体积·重量·采购单价'
  },
  {
    key: 'slabs',
    path: '/slabs',
    icon: '▤',
    label: '大板库存',
    hint: '卡片墙·面积汇总·等级与表面处理'
  },
  {
    key: 'orders',
    path: '/orders',
    icon: '⌁',
    label: '加工工单',
    hint: '锯切方式·计划/已完成板数·批次谱系'
  },
  {
    key: 'saw',
    path: '/saw',
    icon: '⛓',
    label: '锯切批次',
    hint: '开锯冻结·逐片交接·差异待核·收口谱系'
  },
  {
    key: 'deliveries',
    path: '/deliveries',
    icon: '➜',
    label: '出库发货',
    hint: '客户·车牌·大板明细·金额汇总'
  },
  {
    key: 'quality',
    path: '/quality',
    icon: '⛔',
    label: '质量处置',
    hint: '隔离·复检放行·报废收口·发货冻结'
  }
]

const routes = [
  { path: '/', redirect: '/layout' },
  { path: '/layout', name: 'layout', component: () => import('../views/LayoutView.vue') },
  { path: '/blocks', name: 'blocks', component: () => import('../views/BlockView.vue') },
  { path: '/slabs', name: 'slabs', component: () => import('../views/SlabView.vue') },
  { path: '/orders', name: 'orders', component: () => import('../views/CuttingOrderView.vue') },
  { path: '/saw', name: 'saw', component: () => import('../views/SawBatchView.vue') },
  { path: '/deliveries', name: 'deliveries', component: () => import('../views/DeliveryView.vue') },
  { path: '/quality', name: 'quality', component: () => import('../views/QualityHoldView.vue') },
  { path: '/:pathMatch(.*)*', redirect: '/layout' }
]

export default createRouter({
  history: createWebHistory(),
  routes
})
