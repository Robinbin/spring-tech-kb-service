import { NextRequest, NextResponse } from "next/server";
import { apiError, backendFetch, clearSession, SESSION_COOKIE, validOrigin } from "@/lib/backend";

export async function POST(request: NextRequest, context: { params: Promise<{ action: string }> }) {
  if (!validOrigin(request)) return apiError("请求来源不被允许。", 403);
  const { action } = await context.params;
  if (action === "logout") return clearSession(NextResponse.json({ ok: true }));
  if (action !== "login" && action !== "register") return apiError("接口不存在。", 404);
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return apiError("请求必须是有效 JSON。", 400);
  }
  try {
    const upstream = await backendFetch(`/api/auth/${action}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(15_000),
    });
    if (!upstream.ok) {
      const message = upstream.status === 401 ? "登录信息不正确。"
        : upstream.status === 409 ? "邮箱或用户名已被注册。"
        : upstream.status === 429 ? "请求过于频繁，请稍后重试。"
        : "认证失败，请检查输入或稍后重试。";
      return apiError(message, upstream.status);
    }
    const result = await upstream.json();
    const expiresAt = Date.parse(result.expiresAt);
    if (typeof result.accessToken !== "string" || !Number.isFinite(expiresAt) || expiresAt <= Date.now()) {
      console.error("Authentication backend returned an invalid session");
      return apiError("认证服务返回无效会话。", 502);
    }
    const response = NextResponse.json({ user: result.user, expiresAt: result.expiresAt },
      { status: action === "register" ? 201 : 200 });
    response.cookies.set(SESSION_COOKIE, result.accessToken, {
      httpOnly: true,
      secure: process.env.NODE_ENV === "production",
      sameSite: "lax",
      path: "/",
      expires: new Date(expiresAt),
    });
    return response;
  } catch (error) {
    console.error("Authentication backend unavailable", error);
    return apiError("无法连接认证服务。", 502);
  }
}
