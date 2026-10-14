<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { dashboardApi } from '../api'

/**
 * 库存实时看板。
 *
 * 同时展示「数据库真值」与「内存预扣余额」：在 memory 策略下两者会有短暂差异，
 * 这里把「最终一致」这个取舍可视化出来，而不是藏起来。
 */
const board = ref([])
const queue = ref(null)
const error = ref('')
const autoRefresh = ref(true)
let timer = null

async function load() {
  try {
    const [b, q] = await Promise.all([dashboardApi.stockBoard(20), dashboardApi.queue()])
    board.value = b
    queue.value = q
    error.value = ''
  } catch (e) {
    error.value = e.message
  }
}

onMounted(() => {
  load()
  timer = setInterval(() => {
    if (autoRefresh.value) load()
  }, 2000)
})
onUnmounted(() => clearInterval(timer))
</script>

<template>
  <div>
    <div class="panel">
      <h2>
        库存实时看板
        <span class="hint">GET /api/dashboard/stock-board，每 2 秒自动刷新</span>
      </h2>
      <div class="toolbar">
        <button @click="load">立即刷新</button>
        <label class="muted">
          <input type="checkbox" v-model="autoRefresh" style="width: auto" />
          自动刷新
        </label>
        <span class="error-text" v-if="error">{{ error }}</span>
      </div>

      <div class="grid-4" v-if="queue">
        <div class="stat-card">
          <div class="k">队列当前积压</div>
          <div class="v">{{ queue.backlog }}</div>
        </div>
        <div class="stat-card">
          <div class="k">累计入队</div>
          <div class="v">{{ queue.enqueuedCount }}</div>
        </div>
        <div class="stat-card">
          <div class="k">峰值积压（削峰水位）</div>
          <div class="v">{{ queue.mqStats ? queue.mqStats.peakBacklog : 0 }}</div>
        </div>
        <div class="stat-card">
          <div class="k">平均批量</div>
          <div class="v">{{ queue.mqStats ? queue.mqStats.avgBatchSize : 0 }}</div>
        </div>
        <div class="stat-card">
          <div class="k">队列满同步兜底</div>
          <div class="v">{{ queue.syncFallbackCount }}</div>
        </div>
        <div class="stat-card">
          <div class="k">唯一索引拦截重复</div>
          <div class="v">{{ queue.duplicateCount }}</div>
        </div>
        <div class="stat-card">
          <div class="k">最大队列延迟 (ms)</div>
          <div class="v">{{ queue.maxQueueLatencyMillis }}</div>
        </div>
        <div class="stat-card">
          <div class="k">死信数</div>
          <div class="v">{{ queue.mqStats ? queue.mqStats.deadLetterCount : 0 }}</div>
        </div>
      </div>
    </div>

    <div class="panel">
      <h2>活动库存明细</h2>
      <table>
        <thead>
          <tr>
            <th>活动 ID</th>
            <th>标题</th>
            <th>总库存</th>
            <th>数据库剩余</th>
            <th>内存余额</th>
            <th>已售</th>
            <th>售罄进度</th>
            <th>订单数</th>
            <th>已支付</th>
            <th>状态</th>
            <th>库存策略</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="b in board" :key="b.activityId">
            <td>{{ b.activityId }}</td>
            <td>{{ b.title }}</td>
            <td>{{ b.totalStock }}</td>
            <td>{{ b.stock }}</td>
            <td>
              <span :class="b.memoryStock !== b.stock ? 'tag warn' : ''">{{ b.memoryStock }}</span>
            </td>
            <td>{{ b.soldCount }}</td>
            <td>{{ (b.soldRatio * 100).toFixed(1) }}%</td>
            <td>{{ b.orderCount }}</td>
            <td>{{ b.paidCount }}</td>
            <td>{{ b.statusText }}</td>
            <td><span class="tag">{{ b.stockStrategy }}</span></td>
          </tr>
          <tr v-if="board.length === 0">
            <td colspan="11" class="muted">暂无数据</td>
          </tr>
        </tbody>
      </table>
      <p class="muted">
        说明：<code>db-cas</code> 策略下「数据库剩余」与「内存余额」始终一致；
        <code>memory</code> 策略下两者可能有短暂差异（异步落库尚未完成），
        这是最终一致性的直接体现。
      </p>
    </div>
  </div>
</template>
