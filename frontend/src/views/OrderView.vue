<script setup>
import { ref, onMounted } from 'vue'
import { dashboardApi, seckillApi, metricsApi } from '../api'

/** 订单列表：分页、按活动/状态过滤、模拟支付与取消。 */
const list = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(15)
const filterActivityId = ref('')
const filterStatus = ref('')
const error = ref('')
const message = ref('')
const deadLetters = ref([])
const deadLetterTotal = ref(0)

const statusText = (s) => ({ 0: '待支付', 1: '已支付', 2: '已取消', 3: '已关闭' }[s] || '未知')
const statusClass = (s) => ({ 0: 'warn', 1: 'ok', 2: '', 3: 'danger' }[s] || '')

async function load() {
  try {
    const data = await dashboardApi.orders({
      activityId: filterActivityId.value || undefined,
      status: filterStatus.value === '' ? undefined : Number(filterStatus.value),
      page: page.value,
      size: size.value
    })
    list.value = data.list
    total.value = data.total
    error.value = ''
  } catch (e) {
    error.value = e.message
  }
}

async function loadDeadLetters() {
  try {
    const data = await metricsApi.deadLetters(undefined, 1, 10)
    deadLetters.value = data.list
    deadLetterTotal.value = data.total
  } catch (e) {
    error.value = e.message
  }
}

async function pay(order) {
  try {
    await seckillApi.pay({
      orderNo: order.orderNo,
      payChannel: 'MOCK',
      paidAmountCents: order.amountCents
    })
    message.value = `订单 ${order.orderNo} 支付成功`
    await load()
  } catch (e) {
    error.value = e.message
  }
}

async function cancel(order) {
  try {
    await seckillApi.cancel(order.orderNo)
    message.value = `订单 ${order.orderNo} 已取消，库存已回补`
    await load()
  } catch (e) {
    error.value = e.message
  }
}

function changePage(delta) {
  const next = page.value + delta
  if (next < 1 || next > Math.max(1, Math.ceil(total.value / size.value))) return
  page.value = next
  load()
}

function yuan(cents) {
  return (cents / 100).toFixed(2)
}

onMounted(async () => {
  await load()
  await loadDeadLetters()
})
</script>

<template>
  <div>
    <div class="panel">
      <h2>订单列表 <span class="hint">GET /api/dashboard/orders</span></h2>
      <div class="toolbar">
        <input v-model="filterActivityId" placeholder="活动 ID 过滤" style="width: 140px" />
        <select v-model="filterStatus" style="width: 130px">
          <option value="">全部状态</option>
          <option value="0">待支付</option>
          <option value="1">已支付</option>
          <option value="2">已取消</option>
        </select>
        <button @click="page = 1; load()">查询</button>
        <button @click="load">刷新</button>
        <span class="muted" v-if="message">{{ message }}</span>
      </div>
      <p class="error-text" v-if="error">{{ error }}</p>

      <table>
        <thead>
          <tr>
            <th>订单号</th>
            <th>用户</th>
            <th>活动</th>
            <th>数量</th>
            <th>金额（元）</th>
            <th>状态</th>
            <th>下单路径</th>
            <th>创建时间</th>
            <th>支付超时</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="o in list" :key="o.orderNo">
            <td>{{ o.orderNo }}</td>
            <td>{{ o.userId }}</td>
            <td>{{ o.activityId }}</td>
            <td>{{ o.quantity }}</td>
            <td>{{ yuan(o.amountCents) }}</td>
            <td><span class="tag" :class="statusClass(o.status)">{{ statusText(o.status) }}</span></td>
            <td><span class="tag">{{ o.createPath }}</span></td>
            <td class="muted">{{ o.createdAt }}</td>
            <td class="muted">{{ o.expireAt }}</td>
            <td>
              <button v-if="o.status === 0" class="primary" @click="pay(o)">模拟支付</button>
              <button v-if="o.status === 0" class="danger" style="margin-left: 6px" @click="cancel(o)">
                取消
              </button>
            </td>
          </tr>
          <tr v-if="list.length === 0">
            <td colspan="10" class="muted">暂无数据</td>
          </tr>
        </tbody>
      </table>

      <div class="toolbar" style="margin-top: 12px">
        <button @click="changePage(-1)" :disabled="page <= 1">上一页</button>
        <span class="muted">
          第 {{ page }} / {{ Math.max(1, Math.ceil(total / size)) }} 页，共 {{ total }} 条
        </span>
        <button @click="changePage(1)" :disabled="page >= Math.ceil(total / size)">下一页</button>
      </div>
    </div>

    <div class="panel">
      <h2>
        死信队列
        <span class="hint">共 {{ deadLetterTotal }} 条，消费重试超过上限的消息会落到这里</span>
      </h2>
      <div class="toolbar">
        <button @click="loadDeadLetters">刷新</button>
      </div>
      <table>
        <thead>
          <tr>
            <th>ID</th>
            <th>主题</th>
            <th>消息键</th>
            <th>尝试次数</th>
            <th>错误信息</th>
            <th>时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="d in deadLetters" :key="d.id">
            <td>{{ d.id }}</td>
            <td>{{ d.topic }}</td>
            <td>{{ d.msgKey }}</td>
            <td>{{ d.attempt }}</td>
            <td class="muted">{{ d.errorMessage }}</td>
            <td class="muted">{{ d.createdAt }}</td>
          </tr>
          <tr v-if="deadLetters.length === 0">
            <td colspan="6" class="muted">暂无死信（这是好事）</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
