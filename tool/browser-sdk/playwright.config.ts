import { defineConfig, devices } from '@playwright/test';
import { resolve } from 'node:path';

const PORT = 18081;

// The command line goes through a shell (cmd.exe on Windows), so paths are double-quoted as they are, backslashes included.
const quote = (arg: string) => `"${arg}"`;

export default defineConfig({
  testDir: './e2e',
  outputDir: './build/playwright/test-results',
  reporter: [['list'], ['html', { open: 'never', outputFolder: './build/playwright/report' }]],
  fullyParallel: false,
  workers: 1,
  retries: 0,
  use: {
    baseURL: `http://localhost:${PORT}`,
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  // Vite serves the fixture page straight from the sources, with the Node that runs Playwright (Gradle's own).
  webServer: {
    command: [
      quote(process.execPath),
      quote(resolve(import.meta.dirname, 'node_modules/vite/bin/vite.js')),
      quote(resolve(import.meta.dirname, 'e2e/fixture')),
      '--config',
      quote(resolve(import.meta.dirname, 'vite.config.ts')),
      '--port',
      String(PORT),
      '--strictPort',
    ].join(' '),
    url: `http://localhost:${PORT}/`,
    reuseExistingServer: false,
    timeout: 60_000,
  },
});
