import { defineConfig, devices } from '@playwright/test';
import { cpSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

const PORT = 18080;

// The jar needs Java 25+: Gradle's e2eTest passes its toolchain's java in STACK_VIEWER_JAVA; otherwise `java` on PATH.
const java = process.env.STACK_VIEWER_JAVA ?? 'java';

// The command line goes through a shell (cmd.exe on Windows), so paths are double-quoted as they are, backslashes included.
const quote = (arg: string) => `"${arg}"`;

// The backend writes into the workspace (layouts, notes), so it runs on a throwaway copy and the committed fixtures stay clean.
// The config is loaded again by every worker: the copy is made once and handed down through the environment.
const workspace = (process.env.E2E_WORKSPACE ??= (() => {
  const copy = mkdtempSync(join(tmpdir(), 'stack-viewer-e2e-'));
  cpSync(resolve(import.meta.dirname, 'e2e/fixtures/stack'), copy, { recursive: true });
  return copy;
})());

export default defineConfig({
  testDir: './e2e',
  outputDir: './build/playwright/test-results',
  reporter: [['list'], ['html', { open: 'never', outputFolder: './build/playwright/report' }]],
  // the tests share one backend and its workspace, so they run one after another
  fullyParallel: false,
  workers: 1,
  retries: 0,
  use: {
    baseURL: `http://localhost:${PORT}`,
    trace: 'retain-on-failure',
    viewport: { width: 1360, height: 820 },
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 1360, height: 820 } } }],
  webServer: {
    command: [
      quote(java),
      '-jar',
      quote(resolve(import.meta.dirname, '../viewer-backend/build/libs/stack-viewer.jar')),
      `--workspace=${quote(workspace)}`,
      `--recordings=${quote(resolve(import.meta.dirname, 'e2e/fixtures/recordings'))}`,
      `--server.port=${PORT}`,
    ].join(' '),
    url: `http://localhost:${PORT}/api/workspace`,
    reuseExistingServer: false,
    timeout: 60_000,
  },
});
