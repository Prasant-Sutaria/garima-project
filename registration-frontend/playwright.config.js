import { defineConfig } from '@playwright/test';

const stubPort = 3101;
const appPort = 3100;

export default defineConfig({
  testDir: './e2e',
  testMatch: '**/*.spec.js',
  fullyParallel: false,
  workers: 1,
  reporter: 'list',
  use: {
    baseURL: `http://127.0.0.1:${appPort}`,
    trace: 'retain-on-failure',
  },
  webServer: [
    {
      command: 'node e2e/stub-backend.js',
      url: `http://127.0.0.1:${stubPort}/health`,
      env: { STUB_PORT: String(stubPort) },
      reuseExistingServer: false,
    },
    {
      command: 'node server.js',
      url: `http://127.0.0.1:${appPort}/`,
      env: { PORT: String(appPort), BACKEND_URL: `http://127.0.0.1:${stubPort}` },
      reuseExistingServer: false,
    },
  ],
});
