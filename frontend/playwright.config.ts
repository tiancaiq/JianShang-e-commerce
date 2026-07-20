import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  retries: process.env['CI'] ? 1 : 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  globalSetup: './e2e/global-setup.ts',
  use: {
    baseURL: process.env['E2E_BASE_URL'] || 'http://localhost:4200',
    screenshot: 'only-on-failure',
    trace: 'on-first-retry',
    video: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  webServer: process.env['E2E_START_FRONTEND'] === 'true'
    ? {
        command: 'npm run start',
        url: process.env['E2E_BASE_URL'] || 'http://localhost:4200',
        reuseExistingServer: !process.env['CI'],
        stdout: 'ignore',
        stderr: 'pipe',
      }
    : undefined,
});
