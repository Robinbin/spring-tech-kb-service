"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { apiFetch } from "@/lib/client-api";
import { safeUrl, type KnowledgeDetail } from "@/lib/types";

export function KnowledgeDetailView({ id }: { id: string }) {
  const [detail, setDetail] = useState<KnowledgeDetail | null>(null);
  const [error, setError] = useState("");
  useEffect(() => {
    const controller = new AbortController();
    apiFetch(`/api/backend/knowledge/${encodeURIComponent(id)}`, { signal: controller.signal })
      .then((response) => response.json())
      .then(setDetail)
      .catch((reason: unknown) => {
        if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : "加载失败。");
      });
    return () => controller.abort();
  }, [id]);

  return <section className="space-y-6">
    <Link href="/search" className="text-sm underline">返回搜索</Link>
    {error ? <p role="alert" className="text-destructive">{error}</p> : !detail ? <p role="status">正在加载知识内容…</p> : <>
      <h1 className="text-3xl font-bold">{detail.source.title}</h1>
      <div className="flex flex-wrap gap-2"><Badge>{detail.springModule || "模块未标注"}</Badge>
        <Badge variant="secondary">适用版本：{detail.springVersionMin || "未标注下限"} — {detail.springVersionMax || "至今（未标注上限）"}</Badge></div>
      <article className="whitespace-pre-wrap rounded-xl border bg-card p-5 leading-8">{detail.content}</article>
      <Card><CardHeader><CardTitle>来源与许可</CardTitle></CardHeader><CardContent className="space-y-3 text-sm">
        <p>来源：{detail.source.title}</p><p>作者：{detail.source.author || "未标注"}</p>
        <p>许可：{detail.source.license || "未标注，请核实原始来源"}</p>
        {safeUrl(detail.source.url) && <p><a className="underline" href={safeUrl(detail.source.url)} target="_blank" rel="noopener noreferrer">查看来源原文</a></p>}
        {safeUrl(detail.source.licenseUrl) && <p><a className="underline" href={safeUrl(detail.source.licenseUrl)} target="_blank" rel="noopener noreferrer">查看许可条款</a></p>}
      </CardContent></Card>
      <Link className="underline" href="/chat">就此技术继续提问</Link>
    </>}
  </section>;
}
