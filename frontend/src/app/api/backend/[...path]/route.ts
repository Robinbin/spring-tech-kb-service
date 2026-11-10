import { NextRequest, NextResponse } from "next/server";
import { apiError, backendFetch, clearSession, validOrigin } from "@/lib/backend";

export const maxDuration = 150;
const uuid = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";
const routes: Record<string, RegExp[]> = {
  GET: [
    /^(search|users\/me|achievements|achievements\/leaderboard|knowledge\/topics|knowledge\/crawl\/catalog|learning\/plans|operations\/summary)$/,
    new RegExp(`^(knowledge|learning/plans|assessments|generation)/${uuid}$`, "i"),
    new RegExp(`^knowledge/topics/${uuid}/prerequisites$`, "i"),
    new RegExp(`^generation/${uuid}/project$`, "i"),
    new RegExp(`^assessments/${uuid}/report$`, "i"),
  ],
  POST: [
    /^(learning\/plans|assessments\/initial|generation\/(examples|comparisons|projects)|knowledge\/crawl)$/,
    new RegExp(`^learning/plans/${uuid}/adapt$`, "i"),
    new RegExp(`^assessments/${uuid}/answers$`, "i"),
    new RegExp(`^assessments/${uuid}/coding/(run|submit)$`, "i"),
  ],
  PATCH: [new RegExp(`^learning/tasks/${uuid}$`, "i")],
};

async function proxy(request: NextRequest, context: { params: Promise<{ path: string[] }> }) {
  const { path } = await context.params;
  const resource = path.join("/");
  if (!routes[request.method]?.some((pattern) => pattern.test(resource))) {
    return apiError("接口不存在。", 404);
  }
  if (request.method !== "GET" && !validOrigin(request)) return apiError("请求来源不被允许。", 403);
  let body: string | undefined;
  if (request.method !== "GET") {
    body = await request.text();
    if (new TextEncoder().encode(body).length > 65_536) return apiError("请求内容过长。", 413);
    try { JSON.parse(body); } catch { return apiError("请求必须是有效 JSON。", 400); }
  }
  try {
    const upstream = await backendFetch(`/api/${resource}${request.nextUrl.search}`, {
      method: request.method,
      body,
      headers: body === undefined ? undefined : { "Content-Type": "application/json" },
      signal: AbortSignal.any([request.signal, AbortSignal.timeout(request.method === "GET" ? 20_000 : 130_000)]),
    });
    if (upstream.status === 401) return clearSession(apiError("登录已过期，请重新登录。", 401));
    if (!upstream.ok) return apiError(
      upstream.status === 404 ? "未找到内容或无权访问。"
        : upstream.status === 403 ? "没有执行此操作的权限。"
        : upstream.status === 410 ? "引用来源已撤销审核，请重新生成内容。"
        : upstream.status === 409 && resource === "assessments/initial"
          ? "所选主题暂无足够的人工审核选择题，请联系管理员录入后再试。"
        : upstream.status === 409 ? "操作与当前状态冲突，请检查前置任务、重复提交或审核题库。"
        : upstream.status === 422 ? "可用知识或已审核题目不足，无法完成操作。"
        : upstream.status === 400 ? "输入不符合要求，请检查后重试。"
        : upstream.status === 429 ? "请求过于频繁，请稍后重试。"
        : "服务请求失败，请稍后重试。",
      upstream.status,
    );
    if (upstream.headers.get("content-type")?.startsWith("application/zip")) {
      return new NextResponse(upstream.body, { headers: {
        "Content-Type": "application/zip",
        "Content-Disposition": upstream.headers.get("content-disposition") ?? 'attachment; filename="spring-project.zip"',
        "Cache-Control": "no-store",
        ...(upstream.headers.get("x-generation-id") ? { "X-Generation-Id": upstream.headers.get("x-generation-id")! } : {}),
      } });
    }
    if (upstream.status === 204) return new NextResponse(null, { status: 204 });
    return NextResponse.json(await upstream.json(), { status: upstream.status });
  } catch (error) {
    console.error("Backend API request failed", error);
    return apiError("无法连接知识库服务。", 502);
  }
}

export const GET = proxy;
export const POST = proxy;
export const PATCH = proxy;
