/**
 * 后端 API 封装。
 *
 * 统一处理 ApiResponse 结构（code/message/data），非 0 业务码抛异常，
 * 让调用方只需要关心 data。
 */

const BASE = ''

/**
 * 解析统一响应体。
 *
 * @param {Response} response fetch 响应
 * @returns {Promise<any>} 业务数据
 */
export async function parseResponse(response) {
  const text = await response.text()
  let body = null
  try {
    body = text ? JSON.parse(text) : null
  } catch (e) {
    throw new Error(`响应不是合法 JSON（HTTP ${response.status}）: ${text.slice(0, 200)}`)
  }
  if (!response.ok) {
    const message = body && body.message ? body.message : `HTTP ${response.status}`
    const error = new Error(message)
    error.status = response.status
    error.code = body ? body.code : -1
    throw error
  }
  if (body && body.code !== 0) {
    const error = new Error(body.message || '业务处理失败')
    error.code = body.code
    error.status = response.status
    throw error
  }
  return body ? body.data : null
}

async function request(path, options = {}) {
  const response = await fetch(BASE + path, {
    headers: { 'Content-Type': 'application/json' },
    ...options
  })
  return parseResponse(response)
}

// ---------------- 商品 ----------------
export const productApi = {
  list: (page = 1, size = 20) => request(`/api/admin/products?page=${page}&size=${size}`),
  get: (id) => request(`/api/admin/products/${id}`),
  create: (body) => request('/api/admin/products', { method: 'POST', body: JSON.stringify(body) }),
  updateStatus: (id, status) =>
    request(`/api/admin/products/${id}/status?status=${status}`, { method: 'POST' })
}

// ---------------- 活动 ----------------
export const activityApi = {
  list: (page = 1, size = 20) => request(`/api/admin/activities?page=${page}&size=${size}`),
  get: (id) => request(`/api/admin/activities/${id}`),
  create: (body) => request('/api/admin/activities', { method: 'POST', body: JSON.stringify(body) }),
  updateStatus: (id, status) =>
    request(`/api/admin/activities/${id}/status?status=${status}`, { method: 'POST' }),
  warmUp: (id) => request(`/api/admin/activities/${id}/warmup`, { method: 'POST' })
}

// ---------------- 订单与看板 ----------------
export const dashboardApi = {
  orders: ({ activityId, status, page = 1, size = 20 } = {}) => {
    const params = new URLSearchParams({ page: String(page), size: String(size) })
    if (activityId) params.set('activityId', String(activityId))
    if (status !== undefined && status !== null && status !== '') params.set('status', String(status))
    return request(`/api/dashboard/orders?${params.toString()}`)
  },
  order: (orderNo) => request(`/api/dashboard/orders/${orderNo}`),
  stockBoard: (limit = 20) => request(`/api/dashboard/stock-board?limit=${limit}`),
  queue: () => request('/api/dashboard/queue')
}

// ---------------- 指标 ----------------
export const metricsApi = {
  full: () => request('/api/metrics'),
  summary: () => request('/api/metrics/summary'),
  endpoints: () => request('/api/metrics/endpoints'),
  deadLetters: (topic, page = 1, size = 20) => {
    const params = new URLSearchParams({ page: String(page), size: String(size) })
    if (topic) params.set('topic', topic)
    return request(`/api/metrics/dead-letters?${params.toString()}`)
  },
  reset: () => request('/api/metrics/reset', { method: 'POST' })
}

// ---------------- 秒杀 ----------------
export const seckillApi = {
  order: (body) => request('/api/seckill/orders', { method: 'POST', body: JSON.stringify(body) }),
  pay: (body) => request('/api/seckill/pay/callback', { method: 'POST', body: JSON.stringify(body) }),
  cancel: (orderNo) => request(`/api/seckill/orders/${orderNo}/cancel`, { method: 'POST' })
}

// ---------------- 实验 ----------------
export const labApi = {
  verifyLock: (threads = 32, loops = 200) =>
    request(`/api/lab/lock/verify?threads=${threads}&loops=${loops}`, { method: 'POST' }),
  probeRateLimit: (algorithm = 'token-bucket', requests = 5000) =>
    request(`/api/lab/rate-limit/probe?algorithm=${algorithm}&requests=${requests}`, { method: 'POST' }),
  verifyCache: (keys = 100, accesses = 20000) =>
    request(`/api/lab/cache/verify?keys=${keys}&accesses=${accesses}`, { method: 'POST' }),
  peakShaving: (count = 20000) =>
    request(`/api/lab/mq/peak-shaving?count=${count}`, { method: 'POST' }),
  ringBufferBench: (count = 1000000) =>
    request(`/api/lab/ring-buffer/bench?count=${count}`, { method: 'POST' })
}
