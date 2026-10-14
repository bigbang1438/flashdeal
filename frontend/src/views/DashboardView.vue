<script setup>
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
import * as echarts from 'echarts'
import { metricsApi } from '../api'

/**
 * 指标仪表盘：QPS / P99 / 缓存命中率 实时图表。
 *
 * 数据全部来自后端 /api/metrics 与 /api/metrics/summary，
 * 前端只做展示与滑动窗口拼接，不做任何数字加工。
 */
const summary = ref(null)
const snapshot = ref(null)
const error = ref('')
const windowSize = 60

const qpsChart = ref(null)
const latencyChart = ref(null)
const cacheChart = ref(null)

let qpsInstance = null
let latencyInstance = null
let cacheInstance = null
let timer = null

const qpsSeries = []
const p50Series = []
const p95Series = []
const p99Series = []
const hitSeries = []
const labels = []

const chartBase = {
  backgroundColor: 'transparent',
  grid: { left: 48, right: 16, top: 28, bottom: 24 },
  tooltip: { trigger: 'axis' },
  xAxis: {
    type: 'category',
    data: [],
    axisLine: { lineStyle: { color: '#2a2f3d' } },
    axisLabel: { color: '#8b93a7', fontSize: 10 }
  },
  yAxis: {
    type: 'value',
    axisLine: { lineStyle: { color: '#2a2f3d' } },
    splitLine: { lineStyle: { color: '#1e2230' } },
    axisLabel: { color: '#8b93a7', fontSize: 10 }
  }
}

function pushSample(arr, value) {
  arr.push(value)
  if (arr.length > windowSize) arr.shift()
}

function renderCharts() {
  if (qpsInstance) {
    qpsInstance.setOption({
      ...chartBase,
      xAxis: { ...chartBase.xAxis, data: [...labels] },
      series: [
        {
          name: 'QPS',
          type: 'line',
          smooth: true,
          showSymbol: false,
          data: [...qpsSeries],
          lineStyle: { color: '#4f8cff', width: 2 },
          areaStyle: { color: 'rgba(79,140,255,0.18)' }
        }
      ]
    })
  }
  if (latencyInstance) {
    latencyInstance.setOption({
      ...chartBase,
      legend: { data: ['P50', 'P95', 'P99'], textStyle: { color: '#8b93a7' }, top: 0 },
      xAxis: { ...chartBase.xAxis, data: [...labels] },
      series: [
        { name: 'P50', type: 'line', smooth: true, showSymbol: false, data: [...p50Series], lineStyle: { color: '#2fbf71' } },
        { name: 'P95', type: 'line', smooth: true, showSymbol: false, data: [...p95Series], lineStyle: { color: '#f5a524' } },
        { name: 'P99', type: 'line', smooth: true, showSymbol: false, data: [...p99Series], lineStyle: { color: '#ef4444' } }
      ]
    })
  }
  if (cacheInstance) {
    cacheInstance.setOption({
      ...chartBase,
      yAxis: { ...chartBase.yAxis, max: 100, axisLabel: { color: '#8b93a7', fontSize: 10, formatter: '{value}%' } },
      xAxis: { ...chartBase.xAxis, data: [...labels] },
      series: [
        {
          name: 'L1 命中率',
          type: 'line',
          smooth: true,
          showSymbol: false,
          data: [...hitSeries],
          lineStyle: { color: '#7c5cff', width: 2 },
          areaStyle: { color: 'rgba(124,92,255,0.18)' }
        }
      ]
    })
  }
}

/** 从端点指标里取所有端点的汇总延迟（按请求量加权取最大值作为保守值）。 */
function aggregateLatency(endpoints) {
  if (!endpoints || endpoints.length === 0) {
    return { p50: 0, p95: 0, p99: 0 }
  }
  let p50 = 0
  let p95 = 0
  let p99 = 0
  for (const e of endpoints) {
    if (!e.latency || e.latency.samples === 0) continue
    p50 = Math.max(p50, e.latency.p50)
    p95 = Math.max(p95, e.latency.p95)
    p99 = Math.max(p99, e.latency.p99)
  }
  return { p50, p95, p99 }
}

async function refresh() {
  try {
    const [s, full] = await Promise.all([metricsApi.summary(), metricsApi.full()])
    summary.value = s
    snapshot.value = full

    const now = new Date().toLocaleTimeString('zh-CN', { hour12: false })
    pushSample(labels, now)
    pushSample(qpsSeries, s.qps1s)

    const lat = aggregateLatency(full.endpoints)
    pushSample(p50Series, lat.p50)
    pushSample(p95Series, lat.p95)
    pushSample(p99Series, lat.p99)
    pushSample(hitSeries, +(s.cacheL1HitRate * 100).toFixed(2))

    renderCharts()
    error.value = ''
  } catch (e) {
    error.value = e.message
  }
}

onMounted(async () => {
  await nextTick()
  qpsInstance = echarts.init(qpsChart.value)
  latencyInstance = echarts.init(latencyChart.value)
  cacheInstance = echarts.init(cacheChart.value)
  await refresh()
  timer = setInterval(refresh, 2000)
})

onUnmounted(() => {
  clearInterval(timer)
  qpsInstance?.dispose()
  latencyInstance?.dispose()
  cacheInstance?.dispose()
})

async function resetMetrics() {
  await metricsApi.reset()
  labels.length = 0
  qpsSeries.length = 0
  p50Series.length = 0
  p95Series.length = 0
  p99Series.length = 0
  hitSeries.length = 0
  await refresh()
}
</script>

<template>
  <div>
    <div class="panel">
      <h2>
        实时指标
        <span class="hint">数据来自 GET /api/metrics，每 2 秒刷新一次，所有数字均由后端真实采集</span>
      </h2>
      <div class="toolbar">
        <button @click="refresh">立即刷新</button>
        <button class="danger" @click="resetMetrics">复位基线</button>
        <span class="error-text" v-if="error">{{ error }}</span>
      </div>

      <div class="grid-4" v-if="summary">
        <div class="stat-card">
          <div class="k">总请求数</div>
          <div class="v">{{ summary.totalRequests }}</div>
        </div>
        <div class="stat-card">
          <div class="k">QPS (1s)</div>
          <div class="v">{{ summary.qps1s }}</div>
        </div>
        <div class="stat-card">
          <div class="k">峰值 QPS</div>
          <div class="v">{{ summary.peakQps1s }}</div>
        </div>
        <div class="stat-card">
          <div class="k">成功率</div>
          <div class="v">{{ (summary.successRate * 100).toFixed(2) }}%</div>
        </div>
        <div class="stat-card">
          <div class="k">客户端错误 (4xx)</div>
          <div class="v">{{ summary.clientErrors }}</div>
        </div>
        <div class="stat-card">
          <div class="k">服务端错误 (5xx)</div>
          <div class="v">{{ summary.serverErrors }}</div>
        </div>
        <div class="stat-card">
          <div class="k">L1 缓存命中率</div>
          <div class="v">{{ (summary.cacheL1HitRate * 100).toFixed(2) }}%</div>
        </div>
        <div class="stat-card">
          <div class="k">综合命中率（含 L2）</div>
          <div class="v">{{ (summary.cacheOverallHitRate * 100).toFixed(2) }}%</div>
        </div>
        <div class="stat-card">
          <div class="k">队列积压</div>
          <div class="v">{{ summary.queueBacklog }}</div>
        </div>
        <div class="stat-card">
          <div class="k">下单成功</div>
          <div class="v">{{ summary.orderSuccess }}</div>
        </div>
        <div class="stat-card">
          <div class="k">库存不足拒绝</div>
          <div class="v">{{ summary.orderStockEmpty }}</div>
        </div>
        <div class="stat-card">
          <div class="k">被限流拒绝</div>
          <div class="v">{{ summary.orderRateLimited }}</div>
        </div>
      </div>
    </div>

    <div class="grid-2">
      <div class="panel">
        <h2>QPS 实时曲线</h2>
        <div class="chart" ref="qpsChart"></div>
      </div>
      <div class="panel">
        <h2>延迟分位（P50 / P95 / P99，单位 ms）</h2>
        <div class="chart" ref="latencyChart"></div>
      </div>
    </div>

    <div class="panel">
      <h2>L1 缓存命中率（%）</h2>
      <div class="chart" ref="cacheChart"></div>
    </div>

    <div class="panel" v-if="snapshot">
      <h2>
        端点明细
        <span class="hint">共 {{ snapshot.endpoints.length }} 个端点，延迟样本来自环形缓冲</span>
      </h2>
      <table>
        <thead>
          <tr>
            <th>端点</th>
            <th>请求数</th>
            <th>成功</th>
            <th>4xx</th>
            <th>5xx</th>
            <th>成功率</th>
            <th>QPS(1s)</th>
            <th>P50</th>
            <th>P95</th>
            <th>P99</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="e in snapshot.endpoints" :key="e.endpoint">
            <td>{{ e.endpoint }}</td>
            <td>{{ e.totalCount }}</td>
            <td>{{ e.successCount }}</td>
            <td>{{ e.clientErrors }}</td>
            <td>{{ e.serverErrors }}</td>
            <td>{{ (e.successRate * 100).toFixed(2) }}%</td>
            <td>{{ e.qps1s }}</td>
            <td>{{ e.latency.p50 }}</td>
            <td>{{ e.latency.p95 }}</td>
            <td>{{ e.latency.p99 }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="grid-2" v-if="snapshot">
      <div class="panel">
        <h2>缓存统计</h2>
        <table>
          <thead>
            <tr>
              <th>层级</th>
              <th>命中</th>
              <th>未命中</th>
              <th>命中率</th>
              <th>条目/容量</th>
              <th>淘汰</th>
              <th>过期</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="c in snapshot.caches" :key="c.name">
              <td>{{ c.name }}</td>
              <td>{{ c.hitCount }}</td>
              <td>{{ c.missCount }}</td>
              <td>{{ (c.hitRate * 100).toFixed(2) }}%</td>
              <td>{{ c.size }} / {{ c.capacity }}</td>
              <td>{{ c.evictionCount }}</td>
              <td>{{ c.expireCount }}</td>
            </tr>
            <tr v-if="snapshot.l2">
              <td>{{ snapshot.l2.backend }}</td>
              <td>{{ snapshot.l2.hitCount }}</td>
              <td>{{ snapshot.l2.missCount }}</td>
              <td>{{ (snapshot.l2.hitRate * 100).toFixed(2) }}%</td>
              <td>{{ snapshot.l2.size }}</td>
              <td>-</td>
              <td>{{ snapshot.l2.expiredCount }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="panel">
        <h2>队列统计（削峰证据）</h2>
        <table v-if="snapshot.queues.length">
          <thead>
            <tr>
              <th>主题</th>
              <th>容量</th>
              <th>已发布</th>
              <th>已消费</th>
              <th>当前积压</th>
              <th>峰值积压</th>
              <th>平均批量</th>
              <th>死信</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="q in snapshot.queues" :key="q.topic">
              <td>{{ q.topic }}</td>
              <td>{{ q.capacity }}</td>
              <td>{{ q.publishedCount }}</td>
              <td>{{ q.consumedCount }}</td>
              <td>{{ q.backlog }}</td>
              <td><span class="tag warn">{{ q.peakBacklog }}</span></td>
              <td>{{ q.avgBatchSize }}</td>
              <td>{{ q.deadLetterCount }}</td>
            </tr>
          </tbody>
        </table>
        <p class="muted" v-else>暂无队列</p>
      </div>
    </div>

    <div class="panel" v-if="snapshot">
      <h2>业务计数器</h2>
      <table>
        <thead>
          <tr><th>指标</th><th>数值</th></tr>
        </thead>
        <tbody>
          <tr v-for="(v, k) in snapshot.extra.business" :key="k">
            <td>{{ k }}</td>
            <td>{{ v }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
