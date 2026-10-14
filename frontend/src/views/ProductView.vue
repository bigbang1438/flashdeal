<script setup>
import { ref, onMounted } from 'vue'
import { productApi } from '../api'

/** 商品管理：列表 + 新建 + 上下架。 */
const list = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const loading = ref(false)
const error = ref('')
const message = ref('')

const form = ref({ name: '', description: '', priceCents: 19900, imageUrl: '' })

async function load() {
  loading.value = true
  try {
    const data = await productApi.list(page.value, size.value)
    list.value = data.list
    total.value = data.total
    error.value = ''
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function create() {
  try {
    await productApi.create({
      name: form.value.name,
      description: form.value.description,
      priceCents: Number(form.value.priceCents),
      imageUrl: form.value.imageUrl || null
    })
    message.value = '商品创建成功'
    form.value = { name: '', description: '', priceCents: 19900, imageUrl: '' }
    await load()
  } catch (e) {
    error.value = e.message
  }
}

async function toggleStatus(item) {
  try {
    await productApi.updateStatus(item.id, item.status === 1 ? 0 : 1)
    await load()
  } catch (e) {
    error.value = e.message
  }
}

function changePage(delta) {
  const next = page.value + delta
  if (next < 1) return
  if (next > Math.ceil(total.value / size.value)) return
  page.value = next
  load()
}

function yuan(cents) {
  return (cents / 100).toFixed(2)
}

onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <h2>新建商品 <span class="hint">POST /api/admin/products</span></h2>
      <div class="toolbar">
        <input v-model="form.name" placeholder="商品名（必填）" style="width: 200px" />
        <input v-model="form.description" placeholder="描述" style="width: 220px" />
        <input v-model="form.priceCents" type="number" placeholder="价格（分）" style="width: 130px" />
        <input v-model="form.imageUrl" placeholder="图片 URL" style="width: 200px" />
        <button class="primary" @click="create" :disabled="!form.name">创建</button>
        <span class="muted" v-if="message">{{ message }}</span>
      </div>
      <p class="muted">价格一律用「分」为单位的整数存储，避免浮点误差。</p>
    </div>

    <div class="panel">
      <h2>商品列表 <span class="hint">共 {{ total }} 条</span></h2>
      <div class="toolbar">
        <button @click="load" :disabled="loading">刷新</button>
        <button @click="changePage(-1)" :disabled="page <= 1">上一页</button>
        <span class="muted">第 {{ page }} / {{ Math.max(1, Math.ceil(total / size)) }} 页</span>
        <button @click="changePage(1)" :disabled="page >= Math.ceil(total / size)">下一页</button>
      </div>
      <p class="error-text" v-if="error">{{ error }}</p>
      <table>
        <thead>
          <tr>
            <th>ID</th>
            <th>名称</th>
            <th>描述</th>
            <th>价格（元）</th>
            <th>状态</th>
            <th>创建时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="p in list" :key="p.id">
            <td>{{ p.id }}</td>
            <td>{{ p.name }}</td>
            <td>{{ p.description }}</td>
            <td>{{ yuan(p.priceCents) }}</td>
            <td>
              <span class="tag" :class="p.status === 1 ? 'ok' : 'danger'">
                {{ p.status === 1 ? '上架' : '下架' }}
              </span>
            </td>
            <td>{{ p.createdAt }}</td>
            <td>
              <button @click="toggleStatus(p)">{{ p.status === 1 ? '下架' : '上架' }}</button>
            </td>
          </tr>
          <tr v-if="list.length === 0 && !loading">
            <td colspan="7" class="muted">暂无数据</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
