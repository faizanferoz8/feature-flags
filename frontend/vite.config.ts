/// <reference types="vitest" />
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [react()],
  server: {
    // In development the console runs on Vite and talks to the API on 8080.
    proxy: { '/api': 'http://localhost:8080', '/sdk': 'http://localhost:8080' },
  },
  test: { environment: 'jsdom' },
});
