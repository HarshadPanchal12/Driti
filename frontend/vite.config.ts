import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5178,
    proxy: {
      '/devices': {
        target: 'http://localhost:3008',
        changeOrigin: true,
      },
    },
  },
});
