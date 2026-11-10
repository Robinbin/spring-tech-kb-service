import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./tests",
  fullyParallel: false,
  workers: 1,
  use: { baseURL: "http://localhost:3000", trace: "retain-on-failure" },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: [
    { command: "node tests/mock-backend.mjs", url: "http://localhost:18080/health", reuseExistingServer: false },
    {
      command: "npm run start -- --port 3000",
      url: "http://localhost:3000",
      env: { BACKEND_URL: "http://localhost:18080" },
      reuseExistingServer: false,
      timeout: 60_000,
    },
  ],
});
