import { createUIMessageStream, createUIMessageStreamResponse } from "ai";
import { NextRequest } from "next/server";
import { apiError, backendFetch, clearSession, validOrigin } from "@/lib/backend";
import { readSse } from "@/lib/sse";
import type { RagMessage } from "@/lib/types";

export const maxDuration = 150;

export async function POST(request: NextRequest) {
  if (!validOrigin(request)) return apiError("请求来源不被允许。", 403);
  let body;
  try {
    body = await request.json();
  } catch {
    return apiError("请求必须是有效 JSON。", 400);
  }
  if (!body || typeof body !== "object" || Array.isArray(body)
    || typeof body.query !== "string" || !body.query.trim() || body.query.length > 10_000
    || (body.springModule != null && typeof body.springModule !== "string")
    || (body.springVersion != null && typeof body.springVersion !== "string")) {
    return apiError("请输入有效问题与筛选条件。", 400);
  }
  let upstream: Response;
  try {
    upstream = await backendFetch("/api/chat/rag", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ query: body.query, springModule: body.springModule, springVersion: body.springVersion }),
      signal: AbortSignal.any([request.signal, AbortSignal.timeout(130_000)]),
    });
  } catch (error) {
    console.error("RAG backend unavailable", error);
    return apiError("无法连接问答服务。", 502);
  }
  if (upstream.status === 401) return clearSession(apiError("登录已过期，请重新登录。", 401));
  if (!upstream.ok || !upstream.body) return apiError(
    upstream.status === 429 ? "请求过于频繁，请稍后重试。" : "问答服务暂时不可用。",
    upstream.ok ? 502 : upstream.status,
  );
  const responseBody = upstream.body;
  return createUIMessageStreamResponse({
    stream: createUIMessageStream<RagMessage>({
      execute: async ({ writer }) => {
        const id = crypto.randomUUID();
        let complete = false;
        writer.write({ type: "start", messageId: crypto.randomUUID() });
        writer.write({ type: "text-start", id });
        for await (const event of readSse(responseBody)) {
          const value = JSON.parse(event.data);
          switch (event.event || value.type) {
            case "token":
              if (typeof value.text !== "string") throw new Error("Invalid token event");
              writer.write({ type: "text-delta", id, delta: value.text });
              break;
            case "citation":
              if (!value.citation || typeof value.citation.chunkId !== "string"
                || typeof value.citation.title !== "string" || typeof value.citation.url !== "string") {
                throw new Error("Invalid citation event");
              }
              writer.write({ type: "data-citation", id: value.citation.chunkId, data: value.citation });
              break;
            case "done":
              writer.write({ type: "data-route", data: { route: value.route, cacheHit: Boolean(value.cacheHit) } });
              complete = true;
              break;
            case "error":
              throw new Error("Backend reported a RAG error");
          }
          if (complete) break;
        }
        if (!complete) throw new Error("RAG stream ended before completion");
        writer.write({ type: "text-end", id });
        writer.write({ type: "finish" });
      },
      onError: (error) => {
        console.error("RAG stream failed", error);
        return "回答中断，请重试。";
      },
    }),
    headers: { "Cache-Control": "no-store" },
  });
}
