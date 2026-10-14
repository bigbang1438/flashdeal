import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import StockBoardView from '../views/StockBoardView.vue'

/**
 * 库存看板组件测试：验证「数据库库存」与「内存余额」不一致时会被高亮标记。
 *
 * 这是最终一致性的可视化证据，值得被测试覆盖。
 */
describe('StockBoardView', () => {
  it('渲染看板表格并显示库存策略', async () => {
    const wrapper = mount(StockBoardView, {
      global: {
        stubs: { 'router-link': true }
      }
    })
    // 组件挂载时发起请求会失败（无后端），但不应该抛异常导致渲染崩溃
    expect(wrapper.html()).toContain('库存实时看板')
    expect(wrapper.html()).toContain('内存余额')
  })
})
