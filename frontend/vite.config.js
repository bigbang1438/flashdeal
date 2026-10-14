import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * Vite 配置。
 *
 * - 构建产物输出到 dist/，由 Maven 插件复制到 Spring Boot 的 static 目录实现同源托管；
 * - 开发期用 proxy 把 /api 转发到后端 8080，避免跨域。
 */
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    chunkSizeWarningLimit: 1500
  },
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['src/**/*.spec.js']
  }
})
