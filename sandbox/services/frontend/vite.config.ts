import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// `npm run dev` outside compose: the same same-origin paths nginx.conf proxies in the stack. A browser reload of
// /orders/<id> asks for text/html, which Vite (like nginx) must answer with the app, not with the API.
const spaFallbackForPages = {
  bypass: (req: { headers: { accept?: string } }) => (req.headers.accept?.includes('text/html') ? '/index.html' : undefined),
};

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/stock': 'http://localhost:8081', // inventory-service
      '/orders': { target: 'http://localhost:8080', ...spaFallbackForPages }, // orders-service
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
  },
});
