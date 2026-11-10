"use client";

import Link from "next/link";
import { useRef, useState } from "react";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { apiFetch } from "@/lib/client-api";
import { safeUrl, type SearchResult } from "@/lib/types";

export function SearchPanel() {
  const [results, setResults] = useState<SearchResult[]>([]);
  const [busy, setBusy] = useState(false);
  const [searched, setSearched] = useState(false);
  const [error, setError] = useState("");
  const activeRequest = useRef<AbortController | null>(null);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const query = String(form.get("q")).trim();
    if (!query) { setError("请输入搜索关键词。"); return; }
    activeRequest.current?.abort();
    const controller = new AbortController();
    activeRequest.current = controller;
    const params = new URLSearchParams({ q: query, topK: "20" });
    for (const key of ["module", "springVersion"]) {
      const value = String(form.get(key) ?? "").trim();
      if (value) params.set(key, value);
    }
    setBusy(true); setError(""); setSearched(false); setResults([]);
    try {
      const response = await apiFetch(`/api/backend/search?${params}`, { signal: controller.signal });
      const data = await response.json();
      setResults(data.results); setSearched(true);
    } catch (reason) {
      if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : "搜索失败。");
    } finally { if (!controller.signal.aborted) setBusy(false); }
  }

  return (
    <section className="space-y-6">
      <div><h1 className="text-3xl font-bold">知识搜索</h1><p className="mt-2 text-muted-foreground">按模块和 Spring 版本缩小范围，核对原文依据。</p></div>
      <form className="grid min-w-0 gap-4 sm:grid-cols-2" onSubmit={submit}>
        <div className="space-y-2 sm:col-span-2"><Label htmlFor="q">关键词</Label><Input name="q" id="q" maxLength={1000} placeholder="例如：Bean 生命周期" required /></div>
        <div className="space-y-2"><Label htmlFor="module">模块（可选）</Label><Input name="module" id="module" placeholder="core / mvc / data / security" /></div>
        <div className="space-y-2"><Label htmlFor="springVersion">Spring 版本（可选）</Label><Input name="springVersion" id="springVersion" placeholder="例如：6.1" /></div>
        <Button className="sm:col-span-2" type="submit" disabled={busy}>{busy ? "搜索中…" : "搜索"}</Button>
      </form>
      {error && <p role="alert" className="text-destructive">{error}</p>}
      <div aria-live="polite" aria-busy={busy}>
        {searched && <p className="mb-4 text-sm text-muted-foreground">{results.length ? `找到 ${results.length} 条结果` : "未找到匹配结果，请调整关键词或筛选条件。"}</p>}
        <div className="grid min-w-0 gap-4">
          {results.map((result) => <Card key={result.id}>
            <CardHeader><CardTitle><Link className="underline-offset-4 hover:underline" href={`/knowledge/${encodeURIComponent(result.id)}`}>{result.source.title || "知识片段"}</Link></CardTitle>
              <div className="flex flex-wrap gap-2"><Badge variant="secondary">{result.springVersion || "版本未标注"}</Badge><Badge variant="outline">{result.source.license || "许可未标注"}</Badge></div>
            </CardHeader>
            <CardContent className="space-y-3"><p className="line-clamp-5 whitespace-pre-wrap">{result.content}</p>
              {safeUrl(result.source.url) && <a className="text-sm underline" target="_blank" rel="noopener noreferrer" href={safeUrl(result.source.url)}>查看来源原文</a>}
            </CardContent>
          </Card>)}
        </div>
      </div>
    </section>
  );
}
