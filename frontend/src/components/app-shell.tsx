"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { ThemeProvider, useTheme } from "next-themes";
import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { apiFetch } from "@/lib/client-api";

function Navigation() {
  const pathname = usePathname();
  const { resolvedTheme, setTheme } = useTheme();
  const [username, setUsername] = useState("");
  const [isAdmin, setIsAdmin] = useState(false);
  const [error, setError] = useState("");
  const protectedPage = ["/search", "/chat", "/knowledge", "/plans", "/examples", "/assessment", "/practice", "/report", "/graph", "/achievements", "/operations"].some((prefix) => pathname.startsWith(prefix));
  useEffect(() => {
    if (!protectedPage) return;
    let active = true;
    const check = () => apiFetch("/api/backend/users/me")
      .then((response) => response.json())
      .then((user) => { if (active) { setUsername(user.username); setIsAdmin(user.role === "ADMIN"); setError(""); } })
      .catch((reason: unknown) => { if (active) setError(reason instanceof Error ? reason.message : "会话检查失败。"); });
    void check();
    const interval = setInterval(check, 60_000);
    return () => { active = false; clearInterval(interval); };
  }, [protectedPage, pathname]);

  async function logout() {
    try {
      await apiFetch("/api/auth/logout", { method: "POST" });
      window.location.assign("/login");
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "退出失败。");
    }
  }

  return (
    <>
      <header className="border-b bg-card">
        <nav aria-label="主导航" className="mx-auto flex max-w-5xl flex-wrap items-center gap-3 px-4 py-4">
          <Link className="mr-auto text-lg font-bold tracking-tight" href="/">Spring Tech KB</Link>
          <Link aria-current={pathname === "/search" ? "page" : undefined} href="/search">搜索</Link>
          <Link aria-current={pathname === "/chat" ? "page" : undefined} href="/chat">问答</Link>
          <Link aria-current={pathname.startsWith("/plans") ? "page" : undefined} href="/plans">学习计划</Link>
          <Link aria-current={pathname === "/assessment" ? "page" : undefined} href="/assessment">评估</Link>
          <Link aria-current={pathname === "/examples" ? "page" : undefined} href="/examples">代码示例</Link>
          <Link aria-current={pathname === "/graph" ? "page" : undefined} href="/graph">知识图谱</Link>
          <Link aria-current={pathname === "/achievements" ? "page" : undefined} href="/achievements">我的成就</Link>
          {isAdmin && <Link aria-current={pathname === "/operations" ? "page" : undefined} href="/operations">运营概览</Link>}
          <Button variant="outline" aria-label="切换明暗主题" onClick={() => setTheme(resolvedTheme === "dark" ? "light" : "dark")}>
            明暗主题
          </Button>
          {protectedPage ? (
            <Button variant="ghost" onClick={logout}>退出{username ? ` · ${username}` : ""}</Button>
          ) : <Link href="/login">登录</Link>}
        </nav>
      </header>
      {error && <p role="alert" className="mx-auto max-w-5xl px-4 py-2 text-destructive">{error}</p>}
    </>
  );
}

export function AppShell({ children }: { children: React.ReactNode }) {
  return (
    <ThemeProvider attribute="class" defaultTheme="system" enableSystem>
      <a href="#main" className="sr-only focus:not-sr-only">跳至内容</a>
      <Navigation />
      <main id="main" className="mx-auto min-w-0 max-w-5xl px-4 py-8">{children}</main>
      <footer className="mx-auto max-w-5xl border-t px-4 py-6 text-sm text-muted-foreground">
        基于可溯源的 Spring 知识。AI 回答请结合来源和适用版本核实。
      </footer>
    </ThemeProvider>
  );
}
