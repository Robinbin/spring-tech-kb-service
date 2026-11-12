"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { loginDestination } from "@/lib/client-api";

export function AuthForm({ register = false }: { register?: boolean }) {
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [ready, setReady] = useState(false);
  useEffect(() => { setReady(true); }, []);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError("");
    setBusy(true);
    const form = new FormData(event.currentTarget);
    const password = String(form.get("password"));
    if (new TextEncoder().encode(password).length > 72) {
      setError("密码不能超过 72 个 UTF-8 字节。");
      setBusy(false);
      return;
    }
    try {
      const body = register
        ? { email: form.get("email"), username: form.get("username"), password }
        : { identifier: form.get("identifier"), password };
      const response = await fetch(`/api/auth/${register ? "register" : "login"}`, {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
      });
      const result = await response.json();
      if (!response.ok) throw new Error(result.error ?? "认证失败。");
      const next = new URLSearchParams(window.location.search).get("next");
      window.location.replace(loginDestination(next));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "网络请求失败。");
    } finally { setBusy(false); }
  }

  return (
    <Card className="mx-auto w-full max-w-md">
      <CardHeader><CardTitle>{register ? "创建账号" : "登录知识库"}</CardTitle></CardHeader>
      <CardContent>
        <form className="space-y-5" method="post" onSubmit={submit}>
          {register ? <>
            <div className="space-y-2"><Label htmlFor="email">邮箱</Label>
              <Input id="email" name="email" type="email" autoComplete="email" maxLength={255} required /></div>
            <div className="space-y-2"><Label htmlFor="username">用户名</Label>
              <Input id="username" name="username" autoComplete="username" pattern="[A-Za-z0-9._\-]{3,100}" required />
              <p className="text-xs text-muted-foreground">3–100 位英文字母、数字、点、下划线或连字符。</p></div>
          </> : <div className="space-y-2"><Label htmlFor="identifier">邮箱或用户名</Label>
            <Input id="identifier" name="identifier" autoComplete="username" maxLength={255} required /></div>}
          <div className="space-y-2"><Label htmlFor="password">密码</Label>
            <Input id="password" name="password" type="password" autoComplete={register ? "new-password" : "current-password"}
              minLength={register ? 12 : 1} maxLength={72} required />
            {register && <p className="text-xs text-muted-foreground">至少 12 个字符，最多 72 个 UTF-8 字节。</p>}</div>
          {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
          <Button className="w-full" type="submit" disabled={!ready || busy}>{busy ? "处理中…" : register ? "注册并登录" : "登录"}</Button>
          <p className="text-sm"><Link className="underline" href={register ? "/login" : "/register"}>
            {register ? "已有账号，前往登录" : "没有账号，创建账号"}</Link></p>
        </form>
      </CardContent>
    </Card>
  );
}
