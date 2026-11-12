import { expect, test, type Page } from "@playwright/test";

async function login(page: Page) {
  await page.goto("/login");
  await page.getByLabel("邮箱或用户名").fill("student");
  await page.getByLabel("密码", { exact: true }).fill("correct-horse-battery");
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(page).toHaveURL("/search");
}

test("registration persists an HttpOnly session and logout removes it", async ({ page, context }) => {
  await page.goto("/register");
  await page.getByLabel("邮箱", { exact: true }).fill("student@example.com");
  await page.getByLabel("用户名", { exact: true }).fill("student");
  await page.getByLabel("密码", { exact: true }).fill("correct-horse-battery");
  await page.getByRole("button", { name: "注册并登录" }).click();
  await expect(page).toHaveURL("/search");
  const cookie = (await context.cookies()).find((value) => value.name === "springkb_session");
  expect(cookie?.httpOnly).toBe(true);
  expect(cookie?.sameSite).toBe("Lax");
  expect(await page.evaluate(() => document.cookie)).not.toContain("springkb_session");
  await page.reload();
  await expect(page.getByRole("heading", { name: "知识搜索" })).toBeVisible();
  await page.getByRole("button", { name: /退出/ }).click();
  await expect(page).toHaveURL("/login");
  expect((await context.cookies()).some((value) => value.name === "springkb_session")).toBe(false);
});

test("search, version tags, attribution and knowledge detail", async ({ page }) => {
  await login(page);
  await page.getByLabel("关键词").fill("Bean");
  await page.getByLabel("模块（可选）").fill("core");
  await page.getByLabel("Spring 版本（可选）").fill("6.1");
  await page.getByRole("button", { name: "搜索", exact: true }).click();
  await expect(page.getByText("6.1 - 6.2", { exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "查看来源原文" })).toHaveAttribute("href", "https://docs.spring.io/beans");
  await page.getByRole("link", { name: "Spring Bean Lifecycle" }).click();
  await expect(page.getByRole("heading", { name: "Spring Bean Lifecycle" })).toBeVisible();
  await expect(page.getByText(/适用版本：6.1 — 6.2/)).toBeVisible();
  await expect(page.getByText("许可：APACHE_2")).toBeVisible();
  await expect(page.getByRole("link", { name: "查看许可条款" })).toHaveAttribute("href", "https://www.apache.org/licenses/LICENSE-2.0");
});

test("AI SDK displays partial SSE tokens, citations and completion", async ({ page }) => {
  await login(page);
  await page.goto("/chat");
  await page.getByLabel("你的问题").fill("Bean 生命周期");
  await page.getByRole("button", { name: "发送问题" }).click();
  await expect(page.getByText("第一段", { exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "来源原文", exact: true })).toHaveAttribute("href", "https://docs.spring.io/beans");
  await expect(page.getByText("第一段，第二段。", { exact: true })).toBeVisible();
  await expect(page.getByText("FAST_PATH", { exact: true })).toBeVisible();
});

test("stream errors are visible rather than successful completions", async ({ page }) => {
  await login(page);
  await page.goto("/chat");
  await page.getByLabel("你的问题").fill("stream-error");
  await page.getByRole("button", { name: "发送问题" }).click();
  await expect(page.locator('p[role="alert"]')).toContainText("回答中断，请重试。");
  await expect(page.getByText("FAST_PATH", { exact: true })).toHaveCount(0);
});

test("retrieval fallback stays visible and generation can be stopped", async ({ page }) => {
  await login(page);
  await page.goto("/chat");
  await page.getByLabel("你的问题").fill("fallback");
  await page.getByRole("button", { name: "发送问题" }).click();
  await expect(page.getByText("RETRIEVAL_ONLY", { exact: true })).toBeVisible();
  await page.getByLabel("你的问题").fill("cancel");
  await page.getByRole("button", { name: "发送问题" }).click();
  await expect(page.getByText("第一段", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "停止生成" }).click();
  await expect(page.getByRole("button", { name: "停止生成" })).toHaveCount(0);
  await page.getByLabel("你的问题").fill("next question");
  await expect(page.getByRole("button", { name: "发送问题" })).toBeEnabled();
  await expect(page.locator('p[role="alert"]')).toHaveCount(0);
});

test("invalid credentials and empty search have explicit feedback", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("邮箱或用户名").fill("student");
  await page.getByLabel("密码", { exact: true }).fill("wrong-password");
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(page.locator('p[role="alert"]')).toContainText("登录信息不正确");
  await login(page);
  await page.getByLabel("关键词").fill("empty");
  await page.getByRole("button", { name: "搜索", exact: true }).click();
  await expect(page.getByText("未找到匹配结果，请调整关键词或筛选条件。")).toBeVisible();
});

test("expired sessions redirect and login return URLs cannot leave the app", async ({ page, context }) => {
  await context.addCookies([{ name: "springkb_session", value: "expired", url: "http://localhost:3000", httpOnly: true }]);
  await page.goto("/search");
  await expect(page).toHaveURL(/\/login\?next=/);
  await page.goto("/login?next=https://example.com");
  await page.getByLabel("邮箱或用户名").fill("student");
  await page.getByLabel("密码", { exact: true }).fill("correct-horse-battery");
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(page).toHaveURL("/search");
});

test("mobile pages do not overflow and theme choice persists", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await login(page);
  for (const path of ["/", "/search", "/chat", "/knowledge/11111111-1111-1111-1111-111111111111", "/register", "/login"]) {
    await page.goto(path);
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  }
  await page.getByRole("button", { name: "切换明暗主题" }).click();
  const theme = await page.locator("html").getAttribute("class");
  await page.reload();
  await expect(page.locator("html")).toHaveAttribute("class", theme ?? "");
});

test("cross-origin auth and chat mutations are rejected", async ({ request }) => {
  for (const path of ["/api/auth/login", "/api/auth/logout", "/api/chat"]) {
    const response = await request.post(path, { headers: { Origin: "https://evil.example" }, data: {} });
    expect(response.status()).toBe(403);
  }
});

test("malformed chat input is rejected without contacting the backend", async ({ request }) => {
  for (const data of ["null", "[]", '{"query":42}']) {
    const response = await request.post("/api/chat", {
      headers: { Origin: "http://localhost:3000", "Content-Type": "application/json" }, data,
    });
    expect(response.status()).toBe(400);
  }
});
