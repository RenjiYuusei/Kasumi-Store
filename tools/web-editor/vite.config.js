import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  base: './', // Important for GitHub Pages
  build: {
    target: 'es2020',
    // The editor is served from a public Pages site; shipping sourcemaps only
    // publishes the source and slows the deploy.
    sourcemap: false,
    rollupOptions: {
      output: {
        // React changes far less often than the editor code, so keeping it in
        // its own chunk lets returning users reuse it from cache.
        manualChunks: {
          react: ['react', 'react-dom'],
        },
      },
    },
  },
})
