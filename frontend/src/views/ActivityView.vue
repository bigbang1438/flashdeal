<script setup>
import { ref, onMounted } from 'vue'
import { activityApi, productApi } from '../api'

/** 活动管理：列表 + 创建（含库存预热）+ 上下线 + 手动预热。 */
const list = ref([])
const products = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const error = ref('')
const message = ref('')

const now = new Date()
const toLocalInput = (d) =>
  new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 19).replace('T', ' ')

const form = ref({
  productId: '',
  title: '',
  seckillPriceCents: 9900,
  totalStock: 100,
  startTime: toLocalInput(new Date(now.getTime() - 60000)),
  endTime: toLocalInput(new Date(now.getTime() + 3600000))
})

async function load() {
  try {
    const data = await activityApi.list(page.value, size.value)
    list.value = data.list
    total.value = data.total
    error.value = ''
  } catch (e) {
    error.value = e.message
  }
}

async function loadProducts() {
  try {
    const data = await productApi.list(1, 100)
    products.value = data.list
    if (data.list.length && !form.value.productId) {
      form.value.productId = data.list[0].id
    }
  } catch (e) {
    error.value = e.message
  }
}

async function create() {
  try {
    await activityApi.create({
      productId: Number(form.value.productId),
      title: form.value.title,
      seckillPriceCents: Number(form.value.seckillPriceCents),
      totalStock: Number(form.value.totalStock),
      startTime: form.value.startTime,
      endTime: form.value.endTime
    })
    message.value = '活动创建成功（已自动预热库存到 L1/L2 缓存）'
    form.value.title = ''
    await load()
  } catch (e) {
    error.value = e.message
  }
}

async function warmUp(id) {
  try {
    const stock = await activityApi.warmUp(id)
    message.value = `活动 ${id} 预热完成，当前库存 ${stock}`
    await load()
  } catch (e) {
    error.value = e.message
  }
}

async function setStatus(id, status) {
  try {
    await activityApi.updateStatus(id, status)
    await load()
  } catch (e) {
    error.value = e.message
  }
}

function changePage(delta) {
  const next = page.value + delta
  if (next < 1 || next > Math.ceil(total.value / size.value)) return
  page.value = next
  load()
}

function yuan(cents) {
  return (cents / 100).toFixed(2)
}

function statusTag(status) {
  return { 0: 'warn', 1: 'ok', 2: '', 3: 'danger' }[status] || ''
}

function statusText(status) {
  return { 0: '未开始', 1: '进行中', 2: '已结束', 3: '已下线' }[status] || '未知'
}

onMounted(async () => {
  await loadProducts()
  await load()
})
</script>

<template>
  <div>
    <div class="panel">
      <h2>新建秒杀活动 <span class="hint">POST /api/admin/activities，创建后自动做库存预热</span></h2>
      <div class="toolbar">
        <select v-model="form.productId" style="width: 220px">
          <option v-for="p in products" :key="p.id" :value="p.id">
            #{{ p.id }} {{ p.name }}
          </option>
        </select>
        <input v-model="form.title" placeholder="活动标题（必填）" style="width: 180px" />
        <input v-model="form.seckillPriceCents" type="number" placeholder="秒杀价（分）" style="width: 130px" />
        <input v-model="form.totalStock" type="number" placeholder="库存" style="width: 100px" />
      </div>
      <div class="toolbar">
        <label class="muted">开始</label>
        <input v-model="form.startTime" style="width: 190px" />
        <label class="muted">结束</label>
        <input v-model="form.endTime" style="width: 190px" />
        <button class="primary" @click="create" :disabled="!form.title || !form.productId">创建活动</button>
      </div>
      <p class="muted" v-if="message">{{ message }}</p>
      <p class="error-text" v-if="error">{{ error }}</p>
    </div>

    <div class="panel">
      <h2>活动列表 <span class="hint">共 {{ total }} 条</span></h2>
      <div class="toolbar">
        <button @click="load">刷新</button>
        <button @click="changePage(-1)" :disabled="page <= 1">上一页</button>
        <span class="muted">第 {{ page }} / {{ Math.max(1, Math.ceil(total / size)) }} 页</span>
        <button @click="changePage(1)" :disabled="page >= Math.ceil(total / size)">下一页</button>
      </div>
      <table>
        <thead>
          <tr>
            <th>ID</th>
            <th>标题</th>
            <th>商品</th>
            <th>秒杀价（元）</th>
            <th>总库存</th>
            <th>剩余</th>
            <th>已售</th>
            <th>进度</th>
            <th>版本号</th>
            <th>状态</th>
            <th>时间窗</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="a in list" :key="a.id">
            <td>{{ a.id }}</td>
            <td>{{ a.title }}</td>
            <td>{{ a.productId }}</td>
            <td>{{ yuan(a.seckillPriceCents) }}</td>
            <td>{{ a.totalStock }}</td>
            <td>{{ a.stock }}</td>
            <td>{{ a.totalStock - a.stock }}</td>
            <td>
              <span class="tag" :class="a.stock === 0 ? 'danger' : 'ok'">
                {{ ((a.totalStock - a.stock) / a.totalStock * 100).toFixed(1) }}%
              </span>
            </td>
            <td>{{ a.version }}</td>
            <td><span class="tag" :class="statusTag(a.status)">{{ statusText(a.status) }}</span></td>
            <td class="muted">{{ a.startTime }} ~ {{ a.endTime }}</td>
            <td>
              <button @click="warmUp(a.id)">预热</button>
              <button @click="setStatus(a.id, 3)" style="margin-left: 6px">下线</button>
            </td>
          </tr>
          <tr v-if="list.length === 0">
            <td colspan="12" class="muted">暂无数据</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
