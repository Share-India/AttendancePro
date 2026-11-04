import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    // Node resolves "localhost" to ::1 first, so the default bind leaves the
    // server listening on the IPv6 loopback only and http://127.0.0.1:5175
    // is refused. Bind every interface so either loopback reaches it.
    host: true,
    port: 5175,
    // Fail rather than quietly picking another port: a dev server that
    // silently moves is a dev server the API's CORS list no longer matches.
    strictPort: true,
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
  },
});
