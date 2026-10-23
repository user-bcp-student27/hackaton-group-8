import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  workers: 1,
  timeout: 30000,
  expect: {timeout: 7000},
  reporter: [['list'], ['html', {open: 'never'}]],
  use: {baseURL: 'http://127.0.0.1:8090', channel: process.env['PLAYWRIGHT_CHANNEL'] ?? 'chrome', trace: 'retain-on-failure', screenshot: 'only-on-failure'},
  projects: [
    {name: 'desktop', use: {...devices['Desktop Chrome']}},
    {name: 'mobile', use: {...devices['Pixel 7']}},
  ],
  webServer: {
    command: 'java -jar ..\\backend\\target\\quarkus-app\\quarkus-run.jar',
    url: 'http://127.0.0.1:8090/api/health',
    env: {QUARKUS_HTTP_PORT: '8090', WALLET_DB_URL: 'jdbc:h2:mem:e2e;DB_CLOSE_DELAY=-1', WALLET_LAB_ENABLED: 'true'},
    reuseExistingServer: false,
    timeout: 30000,
  },
});
