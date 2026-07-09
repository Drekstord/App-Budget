import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { VitePWA } from 'vite-plugin-pwa'
import { viteStaticCopy } from 'vite-plugin-static-copy'

// base './' + HashRouter : l'app fonctionne servie depuis n'importe quel
// sous-chemin statique (GitHub Pages, dossier local…) sans réécriture serveur.
export default defineConfig({
  base: './',
  plugins: [
    react(),
    // Moteur OCR auto-hébergé (aucun CDN) : worker + cœurs WASM copiés dans ocr/.
    viteStaticCopy({
      targets: [
        {
          src: 'node_modules/tesseract.js/dist/worker.min.js',
          dest: 'ocr',
          rename: { stripBase: true },
        },
        {
          src: 'node_modules/tesseract.js-core/tesseract-core-*lstm.wasm*',
          dest: 'ocr',
          rename: { stripBase: true },
        },
      ],
    }),
    VitePWA({
      registerType: 'autoUpdate',
      includeAssets: ['icons/icon.svg'],
      manifest: {
        name: 'App Budget',
        short_name: 'Budget',
        description: 'Gestion de budget personnel — hors ligne et chiffré',
        lang: 'fr',
        start_url: './',
        scope: './',
        display: 'standalone',
        orientation: 'portrait-primary',
        background_color: '#f9f9f7',
        theme_color: '#2a78d6',
        icons: [
          { src: 'icons/icon-192.png', sizes: '192x192', type: 'image/png' },
          { src: 'icons/icon-512.png', sizes: '512x512', type: 'image/png' },
          {
            src: 'icons/icon-maskable-512.png',
            sizes: '512x512',
            type: 'image/png',
            purpose: 'maskable',
          },
        ],
      },
      workbox: {
        globPatterns: ['**/*.{js,css,html,svg,png,woff2}'],
        // Les gros binaires OCR (~10 Mo) ne sont pas pré-chargés : ils sont mis
        // en cache à la première utilisation du scan, puis disponibles hors ligne.
        globIgnores: ['**/ocr/**'],
        navigateFallback: 'index.html',
        runtimeCaching: [
          {
            urlPattern: /\/ocr\//,
            handler: 'CacheFirst',
            options: {
              cacheName: 'ocr-engine',
              expiration: { maxEntries: 12, maxAgeSeconds: 365 * 24 * 3600 },
              cacheableResponse: { statuses: [0, 200] },
            },
          },
        ],
      },
    }),
  ],
  build: {
    rollupOptions: {
      output: {
        manualChunks: {
          recharts: ['recharts'],
          react: ['react', 'react-dom', 'react-router-dom'],
        },
      },
    },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
} as Parameters<typeof defineConfig>[0])
