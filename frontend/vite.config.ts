/// <reference types="vitest/config" />
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defaultClientConditions, defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    // p2p-media-loader-core의 기본 진입점(lib/)은 bittorrent-tracker · streamx를 그대로 import해 브라우저에 없는
    // Node 모듈(events · process)이 필요하다. 이 조건을 켜면 패키지가 폴리필까지 넣어 둔 자체 번들(dist/)을 쓴다.
    conditions: ['p2pml:core-as-bundle', ...defaultClientConditions],
  },
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
      '/ws': { target: 'ws://localhost:8080', ws: true },
    },
  },
  test: {
    environment: 'node',
  },
})
