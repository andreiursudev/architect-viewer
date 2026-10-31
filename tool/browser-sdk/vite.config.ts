/// <reference types="vitest/config" />
import { defineConfig } from 'vite';

// Vitest runs the unit tests in jsdom; the browser tests (e2e/) serve their fixture page with Vite from the sources.
export default defineConfig({
  server: {
    // Vite forwards the page's console to its own output when it detects an agent; the tests read the console themselves.
    forwardConsole: false,
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.ts'],
  },
});
