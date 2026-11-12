import { expect, test, type Page } from "@playwright/test";

async function login(page: Page) {
  await page.goto("/login");
  await page.getByLabel("邮箱或用户名").fill("student");
  await page.getByLabel("密码", { exact: true }).fill("correct-horse-battery");
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(page).toHaveURL("/search");
}

test("three example levels preserve provenance and can be reopened", async ({ page }) => {
  await login(page);
  await page.goto("/examples");
  for (const level of ["CONCEPT", "PRACTICAL", "SOURCE"]) {
    await page.getByLabel("示例需求").fill("Bean 生命周期");
    await page.getByLabel("示例层级").selectOption(level);
    await page.getByRole("button", { name: "生成代码示例" }).click();
    await expect(page.getByRole("heading", { name: "Bean 回调示例" })).toBeVisible();
    await expect(page.getByText(level, { exact: true })).toBeVisible();
    await expect(page.locator("pre")).toContainText("public class BeanExample");
    await expect(page.getByRole("link", { name: "Spring Bean Lifecycle" })).toHaveAttribute("href", "https://docs.spring.io/beans");
  }
  await page.reload();
  await expect(page.getByText("AI 生成内容", { exact: true })).toBeVisible();
  await expect(page.getByText(/Prompt：code-example:v1/)).toBeVisible();
  const pendingDownload = page.waitForEvent("download");
  await page.getByLabel("将当前示例及来源附作文档").check();
  await page.getByRole("button", { name: "下载项目 ZIP" }).click();
  const download = await pendingDownload;
  expect(download.suggestedFilename()).toBe("spring-demo.zip");
});

test("technical comparison and missing approved evidence are explicit", async ({ page }) => {
  await login(page);
  await page.goto("/examples");
  await page.getByLabel("对比对象 A").fill("BeanFactory");
  await page.getByLabel("对比对象 B").fill("ApplicationContext");
  await page.getByRole("button", { name: "生成技术对比" }).click();
  await expect(page.getByRole("heading", { name: "容器技术对比" })).toBeVisible();
  await page.getByLabel("示例需求").fill("no-evidence");
  await page.getByRole("button", { name: "生成代码示例" }).click();
  await expect(page.locator('p[role="alert"]')).toContainText("可用知识或已审核题目不足");
});

test("Phase 2 mutation proxy is allowlisted and checks origin", async ({ request }) => {
  for (const path of ["learning/plans", "assessments/initial", "generation/projects", "assessments/99999999-9999-9999-9999-999999999999/coding/run"]) {
    const response = await request.post(`/api/backend/${path}`, { headers: { Origin: "https://evil.example" }, data: {} });
    expect(response.status()).toBe(403);
  }
  expect((await request.post("/api/backend/assessments/questions", {
    headers: { Origin: "http://localhost:3000" }, data: {},
  })).status()).toBe(404);
  expect((await request.get("/api/backend/internal/prompts")).status()).toBe(404);
});

test("learning plans enforce task prerequisites, persist progress and adapt once", async ({ page }) => {
  await login(page);
  await page.goto("/plans");
  await page.getByRole("checkbox", { name: /Bean 生命周期/ }).check();
  await page.getByLabel("学习目标（可选）").fill("学习 Spring 6.1 容器");
  await page.getByRole("button", { name: "生成学习计划" }).click();
  await expect(page.getByRole("heading", { name: "Spring 容器学习计划", exact: true })).toBeVisible();
  const first = page.getByRole("article", { name: "任务：学习容器基础", exact: true });
  const second = page.getByRole("article", { name: "任务：学习 Bean 生命周期", exact: true });
  await expect(second.getByRole("button", { name: "完成任务" })).toBeDisabled();
  await page.getByRole("button", { name: "根据完成率调整计划" }).click();
  await expect(page.getByRole("article", { name: "任务：针对性复习容器基础", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "根据完成率调整计划" }).click();
  await expect(page.getByRole("article")).toHaveCount(3);
  await first.getByLabel("累计用时（秒）").fill("180");
  await first.getByRole("button", { name: "完成任务" }).click();
  await expect(first.getByText("已完成", { exact: true })).toBeVisible();
  await expect(second.getByRole("button", { name: "完成任务" })).toBeEnabled();
  await second.getByRole("button", { name: "开始任务" }).click();
  await expect(second.getByText("进行中", { exact: true })).toBeVisible();
  await page.reload();
  await expect(first.getByText("已完成", { exact: true })).toBeVisible();
  await expect(first.getByLabel("累计用时（秒）")).toHaveValue("180");
  await expect(page.getByRole("link", { name: "Spring Bean Lifecycle" })).toBeVisible();
});

test("reviewed assessment supports MCQ, coding and open-ended answers", async ({ page }) => {
  await login(page);
  await page.goto("/assessment");
  await page.getByRole("checkbox", { name: /Bean 生命周期/ }).check();
  await page.getByRole("button", { name: "开始摸底" }).click();
  await expect(page.getByText("Bean 由哪个容器管理？", { exact: false })).toBeVisible();
  await expect(page.getByText(/正确答案/)).toHaveCount(0);
  await page.getByRole("radio", { name: "A. Spring 容器" }).check();
  await page.getByRole("button", { name: "提交本题" }).first().click();
  await expect(page.getByText("已提交：回答正确")).toBeVisible();
  await page.getByRole("link", { name: "打开 CodeMirror 编程编辑器" }).click();
  await expect(page.getByRole("heading", { name: "编程练习" })).toBeVisible();
  await page.locator(".cm-content").first().click();
  await page.keyboard.press("Control+A");
  await page.keyboard.type("public class BeanDemo { public static void main(String[] args) { System.out.println(\"Bean 初始化完成\"); } }");
  await page.getByRole("button", { name: "运行检查" }).click();
  await expect(page.getByText("测试 testBeanLifecycle 通过。")).toBeVisible();
  await page.getByRole("button", { name: "提交答案" }).click();
  await expect(page).toHaveURL(/\/assessment\?id=/);
  await page.reload();
  await expect(page.getByText("已答 2 / 3 题")).toBeVisible();
  await page.getByPlaceholder("请输入你的分析或方案。").fill("初始化回调在 Bean 可用前触发，销毁回调在容器关闭前触发。");
  await page.getByRole("button", { name: "提交本题" }).click();
  await expect(page.getByRole("heading", { name: "摸底完成：67 分" })).toBeVisible();
  await expect(page.getByText("正确答案：A", { exact: true })).toHaveCount(1);
  await expect(page.getByRole("link", { name: "根据摸底结果生成学习计划" })).toBeVisible();
  await expect(page.getByRole("link", { name: "查看能力报告" })).toBeVisible();
});

test("missing reviewed questions never produce fake diagnostics", async ({ page }) => {
  await login(page);
  await page.goto("/assessment");
  await page.getByRole("checkbox", { name: /尚无审核题目的主题/ }).check();
  await page.getByRole("button", { name: "开始摸底" }).click();
  await expect(page.locator('p[role="alert"]')).toContainText("暂无足够的人工审核选择题");
  await expect(page.getByRole("button", { name: "提交本题" })).toHaveCount(0);
});

test("Phase 2 pages stay within mobile width", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await login(page);
  for (const path of ["/plans", "/plans/55555555-5555-5555-5555-555555555555", "/assessment", "/practice", "/report?id=99999999-9999-9999-9999-999999999999", "/examples"]) {
    await page.goto(path);
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  }
});
