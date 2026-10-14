<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { metricsApi } from './api'

/** 顶部状态条：每秒轮询精简指标，展示系统实时水位。 */
const summary = ref(null)
const error = ref('')
let timer = null

async function refresh() {
  try {
    summary.value = await metricsApi.summary()
    error.value = ''
  } catch (e) {
    error.value = e.message
  }
}

onMounted(() => {
  refresh()
  timer = setInterval(refresh, 2000)
})
onUnmounted(() => clearInterval(timer))
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <div class="brand">
        <span class="logo">FD</span>
        <div>
          <h1>FlashDeal</h1>
          <p>高并发秒杀交易系统 · 运营台</p>
        </div>
      </div>
      <div class="live-metrics" v-if="summary">
        <div class="metric">
          <span class="label">QPS(1s)</span>
          <strong>{{ summary.qps1s }}</strong>
        </div>
        <div class="metric">
          <span class="label">峰值QPS</span>
          <strong>{{ summary.peakQps1s }}</strong>
        </div>
        <div class="metric">
          <span class="label">成功率</span>
          <strong>{{ (summary.successRate * 100).toFixed(2) }}%</strong>
        </div>
        <div class="metric">
          <span class="label">L1命中率</span>
          <strong>{{ (summary.cacheL1HitRate * 100).toFixed(2) }}%</strong>
        </div>
        <div class="metric">
          <span class="label">队列积压</span>
          <strong>{{ summary.queueBacklog }}</strong>
        </div>
        <div class="metric">
          <span class="label">库存策略</span>
          <strong class="small">{{ summary.stockStrategy }}</strong>
        </div>
      </div>
      <div class="error-badge" v-else-if="error">后端未就绪：{{ error }}</div>
    </header>

    <nav class="sidenav">
      <router-link to="/dashboard">指标仪表盘</router-link>
      <router-link to="/products">商品管理</router-link>
      <router-link to="/activities">活动管理</router-link>
      <router-link to="/stock">库存看板</router-link>
      <router-link to="/orders">订单列表</router-link>
      <router-link to="/lab">技术验证</router-link>
    </nav>

    <main class="content">
      <router-view />
    </main>
  </div>
</template>
