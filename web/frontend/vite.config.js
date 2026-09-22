import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],

  // 构建产物直接落到 Spring Boot 的静态资源目录，由它托管 —— 不引入 node 的独立部署
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
  },

  // 开发时前端在 5173、后端在 8080，转发 /api 避免跨域
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
