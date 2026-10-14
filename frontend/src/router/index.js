import { createRouter, createWebHashHistory } from 'vue-router'
import DashboardView from '../views/DashboardView.vue'
import ProductView from '../views/ProductView.vue'
import ActivityView from '../views/ActivityView.vue'
import StockBoardView from '../views/StockBoardView.vue'
import OrderView from '../views/OrderView.vue'
import LabView from '../views/LabView.vue'

/**
 * 路由用 hash 模式：Spring Boot 同源托管时不需要额外配置 fallback，
 * 刷新任意页面都能正确落到 index.html。
 */
const routes = [
  { path: '/', redirect: '/dashboard' },
  { path: '/dashboard', name: 'dashboard', component: DashboardView, meta: { title: '指标仪表盘' } },
  { path: '/products', name: 'products', component: ProductView, meta: { title: '商品管理' } },
  { path: '/activities', name: 'activities', component: ActivityView, meta: { title: '活动管理' } },
  { path: '/stock', name: 'stock', component: StockBoardView, meta: { title: '库存看板' } },
  { path: '/orders', name: 'orders', component: OrderView, meta: { title: '订单列表' } },
  { path: '/lab', name: 'lab', component: LabView, meta: { title: '技术验证' } }
]

export default createRouter({
  history: createWebHashHistory(),
  routes
})
