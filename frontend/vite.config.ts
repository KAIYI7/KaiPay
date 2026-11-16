import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The demo launcher sets KAIPAY_DEV_API_PORT only for an isolated instance.
const apiPort = process.env.KAIPAY_DEV_API_PORT ?? '28080';
if (!/^[1-9][0-9]{3,4}$/.test(apiPort) || Number(apiPort) < 1024 || Number(apiPort) > 65535) {
  throw new Error('KAIPAY_DEV_API_PORT must be a TCP port number.');
}

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    port: 28081,
    strictPort: true,
    proxy: {
      '/v1': {
        target: `http://localhost:${apiPort}`,
        changeOrigin: true,
        secure: false,
      },
    },
  },
});
