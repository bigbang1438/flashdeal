<script setup>
import { ref } from 'vue'
import { labApi } from '../api'

/**
 * 技术验证页：把「锁、限流、缓存、削峰、环形缓冲」五件事变成可以点击的实验。
 *
 * 每个实验都打真实后端端点，输出真实数字；不在这里做任何模拟。
 */
const lockResult = ref(null)
const rateResult = ref(null)
const cacheResult = ref(null)
const mqResult = ref(null)
const ringResult = ref(null)
const error = ref('')
const busy = ref('')

const lockThreads = ref(32)
const lockLoops = ref(200)
const rateRequests = ref(5000)
const rateAlgorithm = ref('token-bucket')
const cacheKeys = ref(100)
const cacheAccesses = ref(20000)
const mqCount = ref(20000)
const ringCount = ref(1000000)

async function run(name, fn) {
  busy.value = name
  error.value = ''
  try {
    await fn()
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = ''
  }
}

const runLock = () =>
  run('lock', async () => {
    lockResult.value = await labApi.verifyLock(Number(lockThreads.value), Number(lockLoops.value))
  })

const runRate = () =>
  run('rate', async () => {
    rateResult.value = await labApi.probeRateLimit(rateAlgorithm.value, Number(rateRequests.value))
  })

const runCache = () =>
  run('cache', async () => {
    cacheResult.value = await labApi.verifyCache(Number(cacheKeys.value), Number(cacheAccesses.value))
  })

const runMq = () =>
  run('mq', async () => {
    mqResult.value = await labApi.peakShaving(Number(mqCount.value))
  })

const runRing = () =>
  run('ring', async () => {
    ringResult.value = await labApi.ringBufferBench(Number(ringCount.value))
  })
</script>

<template>
  <div>
    <div class="panel">
      <h2>
        技术验证实验
        <span class="hint">每一项都打真实后端端点，返回的是真实运行结果，不是前端模拟</span>
      </h2>
      <p class="error-text" v-if="error">{{ error }}</p>
      <p class="muted">正在执行：{{ busy || '空闲' }}</p>
    </div>

    <div class="panel">
      <h2>① 分布式锁互斥性验证 <span class="hint">POST /api/lab/lock/verify</span></h2>
      <div class="toolbar">
        <label class="muted">线程数</label>
        <input v-model="lockThreads" type="number" style="width: 100px" />
        <label class="muted">每线程循环</label>
        <input v-model="lockLoops" type="number" style="width: 110px" />
        <button class="primary" @click="runLock" :disabled="busy">开始验证</button>
      </div>
      <pre class="result" v-if="lockResult">{{ JSON.stringify(lockResult, null, 2) }}</pre>
      <p class="muted">
        判据：<code>expected === actual</code> 且 <code>lostUpdates === 0</code>。
        锁若失效，并发自增必然丢更新。
      </p>
    </div>

    <div class="panel">
      <h2>② 限流算法验证 <span class="hint">POST /api/lab/rate-limit/probe</span></h2>
      <div class="toolbar">
        <select v-model="rateAlgorithm" style="width: 180px">
          <option value="token-bucket">令牌桶 token-bucket</option>
          <option value="sliding-window">滑动窗口 sliding-window</option>
        </select>
        <label class="muted">请求数</label>
        <input v-model="rateRequests" type="number" style="width: 120px" />
        <button class="primary" @click="runRate" :disabled="busy">开始探测</button>
      </div>
      <pre class="result" v-if="rateResult">{{ JSON.stringify(rateResult, null, 2) }}</pre>
      <p class="muted">判据：<code>allowed + rejected === requests</code>，且放行数受配额约束。</p>
    </div>

    <div class="panel">
      <h2>③ 多级缓存命中率验证 <span class="hint">POST /api/lab/cache/verify</span></h2>
      <div class="toolbar">
        <label class="muted">key 数量</label>
        <input v-model="cacheKeys" type="number" style="width: 100px" />
        <label class="muted">访问次数</label>
        <input v-model="cacheAccesses" type="number" style="width: 120px" />
        <button class="primary" @click="runCache" :disabled="busy">开始验证</button>
      </div>
      <pre class="result" v-if="cacheResult">{{ JSON.stringify(cacheResult, null, 2) }}</pre>
      <p class="muted">
        判据：<code>hits + misses === accesses</code>，<code>hitRate = hits / (hits + misses)</code>。
      </p>
    </div>

    <div class="panel">
      <h2>④ 内存队列削峰验证 <span class="hint">POST /api/lab/mq/peak-shaving</span></h2>
      <div class="toolbar">
        <label class="muted">灌入消息数</label>
        <input v-model="mqCount" type="number" style="width: 120px" />
        <button class="primary" @click="runMq" :disabled="busy">开始灌入</button>
      </div>
      <pre class="result" v-if="mqResult">{{ JSON.stringify(mqResult, null, 2) }}</pre>
      <p class="muted">
        判据：<code>peakBacklog &gt; 0</code>（瞬时峰值被队列吸收），且最终 <code>backlogAfter === 0</code>。
      </p>
    </div>

    <div class="panel">
      <h2>⑤ 环形缓冲裸吞吐 <span class="hint">POST /api/lab/ring-buffer/bench</span></h2>
      <div class="toolbar">
        <label class="muted">生产条数</label>
        <input v-model="ringCount" type="number" style="width: 140px" />
        <button class="primary" @click="runRing" :disabled="busy">开始压测</button>
      </div>
      <pre class="result" v-if="ringResult">{{ JSON.stringify(ringResult, null, 2) }}</pre>
      <p class="muted">判据：<code>produced === consumed</code>，不丢不重。</p>
    </div>
  </div>
</template>
