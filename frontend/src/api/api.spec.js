import { describe, it, expect } from 'vitest'
import { parseResponse } from '../api/index.js'

/**
 * API 层单元测试：重点验证统一响应体的解析与错误处理。
 *
 * 这些用例保证前端不会把「业务失败」当成成功渲染出来。
 */
function mockResponse({ ok = true, status = 200, body }) {
  return {
    ok,
    status,
    text: async () => (typeof body === 'string' ? body : JSON.stringify(body))
  }
}

describe('parseResponse', () => {
  it('code=0 时返回 data', async () => {
    const data = await parseResponse(mockResponse({ body: { code: 0, message: 'ok', data: { id: 7 } } }))
    expect(data).toEqual({ id: 7 })
  })

  it('HTTP 200 但 code 非 0 时抛错并带上业务码', async () => {
    await expect(
      parseResponse(mockResponse({ body: { code: 2004, message: '库存不足', data: null } }))
    ).rejects.toMatchObject({ code: 2004, message: '库存不足' })
  })

  it('HTTP 409 时抛错并带上状态码', async () => {
    await expect(
      parseResponse(
        mockResponse({ ok: false, status: 409, body: { code: 2005, message: '您已参与过该秒杀活动' } })
      )
    ).rejects.toMatchObject({ status: 409, code: 2005 })
  })

  it('HTTP 429 时抛出限流错误', async () => {
    await expect(
      parseResponse(
        mockResponse({ ok: false, status: 429, body: { code: 3001, message: '请求过于频繁，请稍后再试' } })
      )
    ).rejects.toMatchObject({ status: 429, code: 3001 })
  })

  it('非 JSON 响应时给出可读错误', async () => {
    await expect(
      parseResponse(mockResponse({ ok: false, status: 502, body: '<html>Bad Gateway</html>' }))
    ).rejects.toThrow(/不是合法 JSON/)
  })

  it('空响应体返回 null', async () => {
    const data = await parseResponse(mockResponse({ body: '' }))
    expect(data).toBeNull()
  })

  it('data 为 null 时不报错', async () => {
    const data = await parseResponse(mockResponse({ body: { code: 0, message: 'ok', data: null } }))
    expect(data).toBeNull()
  })
})
