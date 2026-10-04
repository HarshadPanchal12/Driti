import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  esbuild: {
    target: 'es2022',
  },
  optimizeDeps: {
    esbuildOptions: {
      target: 'es2022',
    },
  },
  build: {
    target: 'es2022',
  },
  server: {
    port: 5178,
    proxy: {
      '/devices': {
        target: 'http://localhost:3008',
        changeOrigin: true,
      },
      '/vnc': {
        target: 'http://localhost:3008',
        ws: true,
        changeOrigin: true,
      },
    },
  },
});
