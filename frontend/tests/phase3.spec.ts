import { expect, test, type Page } from "@playwright/test";

async function login(page: Page) {
  await page.goto("/login");
  const submit = page.getByRole("button", { name: "登录", exact: true });
  await expect(submit).toBeEnabled({ timeout: 15_000 });
  await page.getByLabel("邮箱或用户名").fill("student");
  await page.getByLabel("密码", { exact: true }).fill("correct-horse-battery");
  await submit.click();
  await expect(page).toHaveURL("/search");
}

test("practice page reports deterministic run feedback", async ({ page }) => {
  await login(page);
  await page.goto("/assessment");
  await page.getByRole("checkbox", { name: /Bean 生命周期/ }).check();
  await page.getByRole("button", { name: "开始摸底" }).click();
  await page.getByRole("link", { name: "打开 CodeMirror 编程编辑器" }).click();
  await page.locator(".cm-content").first().click();
  await page.keyboard.press("Control+A");
  await page.keyboard.type("public class BeanDemo { public static void main(String[] args) { /* TODO */ } }");
  await page.getByRole("button", { name: "运行检查" }).click();
  await expect(page.getByText("测试 testBeanLifecycle 未通过：仍存在 TODO。")).toBeVisible();
  await expect(page.getByText("静态分析提示")).toBeVisible();
});

test("report page renders radar svg and profile", async ({ page }) => {
  await login(page);
  await page.goto("/report?id=99999999-9999-9999-9999-999999999999");
  await expect(page.getByRole("heading", { name: "能力报告" })).toBeVisible();
  await expect(page.getByRole("img", { name: "能力雷达图" })).toBeVisible();
  await expect(page.locator('[data-testid="radar-profile"]')).toHaveAttribute("points", /,/);
  await expect(page.getByText("优势")).toBeVisible();
  await expect(page.getByText("待提升")).toBeVisible();
});
