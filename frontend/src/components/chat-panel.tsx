"use client";

import Link from "next/link";
import { useChat } from "@ai-sdk/react";
import { DefaultChatTransport } from "ai";
import { useEffect, useMemo, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Badge } from "@/components/ui/badge";
import { apiFetch } from "@/lib/client-api";
import { safeUrl, type RagMessage } from "@/lib/types";

export function ChatPanel() {
  const transport = useMemo(() => new DefaultChatTransport<RagMessage>({
    api: "/api/chat",
    fetch: (input, init) => apiFetch(String(input), init),
    prepareSendMessagesRequest: ({ messages, body }) => ({
      body: {
        ...body,
        query: messages.at(-1)?.parts.filter((part) => part.type === "text").map((part) => part.text).join(""),
      },
    }),
  }), []);
  const { messages, sendMessage, status, stop, error } = useChat<RagMessage>({ transport });
  const [query, setQuery] = useState("");
  const [springModule, setModule] = useState("");
  const [springVersion, setVersion] = useState("");
  const end = useRef<HTMLDivElement>(null);
  const busy = status === "streaming" || status === "submitted";
  useEffect(() => { end.current?.scrollIntoView({ block: "nearest" }); }, [messages]);

  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!query.trim() || busy) return;
    void sendMessage({ text: query.trim() }, { body: { springModule, springVersion } });
    setQuery("");
  }

  return <section className="space-y-6">
    <div><h1 className="text-3xl font-bold">知识问答</h1><p className="mt-2 text-muted-foreground">每次提问独立检索。回答逐步生成，请结合引用核实。</p></div>
    <div className="grid gap-4 sm:grid-cols-2">
      <div className="space-y-2"><Label htmlFor="chat-module">模块（可选）</Label><Input id="chat-module" value={springModule} onChange={(event) => setModule(event.target.value)} placeholder="例如：core" disabled={busy} /></div>
      <div className="space-y-2"><Label htmlFor="chat-version">Spring 版本（可选）</Label><Input id="chat-version" value={springVersion} onChange={(event) => setVersion(event.target.value)} placeholder="例如：6.1" disabled={busy} /></div>
    </div>
    <div role="log" aria-label="问答记录" aria-live="polite" className="min-h-48 space-y-4">
      {!messages.length && <p className="rounded-xl border border-dashed p-6 text-muted-foreground">试着提问：Spring Bean 的生命周期有哪些阶段？</p>}
      {messages.map((message) => <article key={message.id} className={`space-y-3 rounded-xl border p-4 ${message.role === "user" ? "bg-secondary" : "bg-card"}`}>
        <p className="text-sm font-semibold">{message.role === "user" ? "你" : "知识助手"}</p>
        {message.parts.map((part, index) => {
          if (part.type === "text") return <p className="whitespace-pre-wrap leading-7" key={index}>{part.text}</p>;
          if (part.type === "data-citation") return <aside key={part.data.chunkId} className="rounded-lg border p-3 text-sm">
            <p className="font-medium">引用：{part.data.title}</p>
            <p className="mt-1 text-muted-foreground">{part.data.springVersion || "版本未标注"} · {part.data.license || "许可未标注"}</p>
            <div className="mt-2 flex flex-wrap gap-4">
              {safeUrl(part.data.url) && <a className="underline" href={safeUrl(part.data.url)} target="_blank" rel="noopener noreferrer">来源原文</a>}
              <Link className="underline" href={`/knowledge/${encodeURIComponent(part.data.chunkId)}`}>知识详情</Link>
            </div>
          </aside>;
          if (part.type === "data-route") return <Badge variant="outline" key={index}>{part.data.route}{part.data.cacheHit ? " · 缓存命中" : ""}</Badge>;
          return null;
        })}
      </article>)}
      <div ref={end} />
    </div>
    {error && <p role="alert" className="text-destructive">{error.message}</p>}
    {busy && <p role="status" className="text-sm text-muted-foreground">正在生成回答…</p>}
    <form onSubmit={submit} className="space-y-3">
      <Label htmlFor="question">你的问题</Label>
      <textarea className="min-h-24 w-full min-w-0 rounded-lg border bg-background p-3 text-sm" id="question"
        value={query} onChange={(event) => setQuery(event.target.value)} maxLength={10_000} required placeholder="输入 Spring 工程问题" />
      <div className="flex flex-wrap gap-3"><Button type="submit" disabled={busy || !query.trim()}>发送问题</Button>
        {busy && <Button variant="outline" type="button" onClick={() => void stop()}>停止生成</Button>}</div>
    </form>
  </section>;
}
