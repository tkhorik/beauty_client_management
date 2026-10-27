import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { VitePWA } from 'vite-plugin-pwa'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), VitePWA({
    strategies: 'injectManifest',
    srcDir: 'worker',
    filename: 'sw.ts',
    injectRegister: false,
    registerType: 'prompt',
    manifestFilename: 'manifest.json',
    manifest: {
      id: '/', name: 'Aura Beauty Log', short_name: 'Aura',
      description: 'Client profiles, visits and before/after photos for your salon.',
      start_url: '/', scope: '/', display: 'standalone', lang: 'ru',
      theme_color: '#f8f7f2', background_color: '#f8f7f2',
      icons: [
        { src: '/pwa/icon-192.png', sizes: '192x192', type: 'image/png', purpose: 'any' },
        { src: '/pwa/icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'any' },
        { src: '/pwa/maskable-192.png', sizes: '192x192', type: 'image/png', purpose: 'maskable' },
        { src: '/pwa/maskable-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
      ],
    },
    includeAssets: ['favicon.svg', 'icons.svg', 'pwa/*.png', 'fonts/*.woff2', 'offline.html'],
    injectManifest: { globPatterns: ['**/*.{js,css,woff2,png,svg}'], globIgnores: ['**/index.html'] },
    devOptions: { enabled: false },
  })],
  server: {
    // In development the app calls the relative path `/api`, exactly like it
    // does in production behind the edge Nginx. This proxy forwards those calls
    // to the locally running Ktor backend, so dev and prod share one code path
    // and CORS is never involved in either environment.
    proxy: {
      '/api': {
        target: process.env.VITE_DEV_API_TARGET ?? 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
      '/uploads': {
        target: process.env.VITE_DEV_API_TARGET ?? 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
})
