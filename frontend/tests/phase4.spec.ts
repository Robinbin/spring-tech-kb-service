import { expect, test, type Page } from "@playwright/test";

async function login(page: Page) {
  await page.goto("/login");
  const submit = page.getByRole("button", { name: "登录", exact: true });
  await page.getByLabel("邮箱或用户名").fill("student");
  await page.getByLabel("密码", { exact: true }).fill("correct-horse-battery");
  await expect(submit).toBeEnabled({ timeout: 15_000 });
  await submit.click();
  await expect(page).toHaveURL("/search");
}

test("knowledge graph loads prerequisite edges and accessible topic details", async ({ page }) => {
  await login(page);
  await page.goto("/graph");
  await expect(page.getByRole("heading", { name: "知识前置关系图" })).toBeVisible();
  await expect(page.getByRole("img", { name: "知识前置关系图" })).toBeVisible();
  await page.getByRole("button", { name: "Bean 生命周期 · core" }).click();
  await expect(page.getByRole("complementary", { name: "主题详情" })).toContainText("容器基础");
  await expect(page.getByText("3 个主题 · 1 条前置关系")).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
});

test("achievements page shows streaks and switches leaderboard period without exposing email", async ({ page }) => {
  await login(page);
  await page.goto("/achievements");
  await expect(page.getByRole("heading", { name: "我的成就" })).toBeVisible();
  await expect(page.getByText("7 天", { exact: true })).toBeVisible();
  await expect(page.getByText("初次探索")).toBeVisible();
  await expect(page.getByText("春日学习者")).toBeVisible();
  await expect(page.getByText("student@example.com")).toHaveCount(0);
  await expect(page.getByText("匿名学习者")).toBeVisible();
  await expect(page.getByText("test.person@example.com")).toHaveCount(0);
  await page.getByLabel("榜单周期").selectOption("monthly");
  await expect(page.getByText("5120 学习分")).toBeVisible();
});

test("operations dashboard displays scoped metrics and refreshes its period", async ({ page }) => {
  await login(page);
  await page.goto("/operations");
  const internalRoute = await page.request.get("/api/backend/internal/operations/summary");
  expect(internalRoute.status()).toBe(404);
  await expect(page.getByRole("heading", { name: "运营概览" })).toBeVisible();
  await expect(page.getByText(/12\.3456/)).toBeVisible();
  await expect(page.getByText("72.0%")).toBeVisible();
  await expect(page.getByText("38.0%")).toBeVisible();
  await expect(page.getByText("CLOSED")).toBeVisible();
  await expect(page.getByText("HALF_OPEN")).toBeVisible();
  await page.getByLabel("统计周期").selectOption("30");
  await expect(page.getByText("45.6789")).toBeVisible();
  await expect(page.getByText("统计周期：近 30 天")).toBeVisible();
  await page.getByRole("checkbox", { name: /Spring Framework Reference/ }).check();
  await page.getByLabel("审批记录编号").fill("CHANGE-1234");
  await page.getByRole("button", { name: "开始抓取" }).click();
  await expect(page.getByRole("status")).toContainText("已处理 1 个来源");
  await expect(page.getByRole("status")).toContainText("12 个知识片段");
});
