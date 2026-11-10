"use client";

import { useEffect, useState } from "react";
import { apiFetch } from "@/lib/client-api";
import type { CatalogItem, CrawlRunResult, OperationsSummary } from "@/lib/phase4-types";

function percent(value: number | null) {
  return value === null ? "暂无数据" : `${(Math.max(0, Math.min(1, value)) * 100).toFixed(1)}%`;
}

function cost(value: number) {
  return new Intl.NumberFormat("zh-CN", {
    style: "currency", currency: "USD", maximumFractionDigits: 4,
  }).format(value);
}

export function OperationsDashboard() {
  const [days, setDays] = useState(7);
  const [isAdmin, setIsAdmin] = useState<boolean | null>(null);
  const [summary, setSummary] = useState<OperationsSummary | null>(null);
  const [catalog, setCatalog] = useState<CatalogItem[]>([]);
  const [selectedKeys, setSelectedKeys] = useState<string[]>([]);
  const [approvalReference, setApprovalReference] = useState("");
  const [crawlResult, setCrawlResult] = useState<CrawlRunResult | null>(null);
  const [crawlError, setCrawlError] = useState("");
  const [crawling, setCrawling] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError("");
    setSummary(null);
    apiFetch("/api/backend/users/me")
      .then((response) => response.json())
      .then(async (user) => {
        if (!active) return;
        const authorized = user?.role === "ADMIN";
        setIsAdmin(authorized);
        if (!authorized) return;
        const [summaryResponse, catalogResponse] = await Promise.all([
          apiFetch(`/api/backend/operations/summary?days=${days}`),
          apiFetch("/api/backend/knowledge/crawl/catalog"),
        ]);
        const [summaryData, catalogData] = await Promise.all([
          summaryResponse.json(), catalogResponse.json(),
        ]);
        if (active) {
          setSummary(summaryData);
          setCatalog(Array.isArray(catalogData) ? catalogData : []);
        }
      })
      .catch((reason: unknown) => {
        if (active) setError(reason instanceof Error ? reason.message : "运营数据加载失败。");
      })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [days]);

  const tiers = summary?.byModelTier ?? [];
  const totalKnownCostUsd = tiers.reduce((total, tier) => total + (tier.knownCostUsd ?? 0), 0);
  const unknownCostCalls = tiers.reduce((total, tier) => total + tier.unknownCostCalls, 0);
  const cacheReadTokens = tiers.reduce((total, tier) => total + tier.cacheReadTokens, 0);
  const uncachedInputTokens = tiers.reduce((total, tier) => total + tier.inputTokens, 0);
  const cacheDenominator = cacheReadTokens + uncachedInputTokens;
  const cacheReadRatio = cacheDenominator ? cacheReadTokens / cacheDenominator : null;
  const totalCalls = tiers.reduce((total, tier) => total + tier.calls, 0);
  const totalBatchCalls = tiers.reduce((total, tier) => total + tier.batchCalls, 0);
  const batchShare = totalCalls ? totalBatchCalls / totalCalls : null;

  async function submitCrawl(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setCrawling(true);
    setCrawlError("");
    setCrawlResult(null);
    try {
      const response = await apiFetch("/api/backend/knowledge/crawl", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          sourceKeys: selectedKeys,
          maxSources: 3,
          approvalReference: approvalReference.trim(),
        }),
      });
      setCrawlResult(await response.json());
    } catch (reason) {
      setCrawlError(reason instanceof Error ? reason.message : "抓取任务失败。");
    } finally {
      setCrawling(false);
    }
  }

  return (
    <section className="space-y-6">
      <header className="flex flex-wrap items-end justify-between gap-4">
        <div className="space-y-2">
          <p className="text-sm text-muted-foreground">仅限管理员</p>
          <h1 className="text-3xl font-bold">运营概览</h1>
          <p className="text-muted-foreground">查看近期成本、提示缓存、批处理和熔断器状态。</p>
        </div>
        <label className="flex items-center gap-2 text-sm">
          统计周期
          <select aria-label="统计周期" className="rounded-md border bg-background px-3 py-2" value={days}
            onChange={(event) => setDays(Number(event.target.value))}>
            <option value={7}>近 7 天</option>
            <option value={14}>近 14 天</option>
            <option value={30}>近 30 天</option>
          </select>
        </label>
      </header>
      {loading && <p role="status">正在加载运营数据…</p>}
      {error && <p role="alert" className="text-destructive">{error}</p>}
      {!loading && !error && isAdmin === false && (
        <p role="alert" className="rounded-lg border p-5">此页面仅向管理员开放。</p>
      )}
      {!loading && !error && isAdmin && summary && (
        <>
          <p className="text-sm text-muted-foreground">统计周期：近 {summary.windowDays} 天</p>
          <div className="grid gap-4 sm:grid-cols-2">
            <article className="rounded-xl border p-5">
              <h2 className="text-sm text-muted-foreground">已知累计成本（USD）</h2>
              <p className="mt-2 text-2xl font-bold">{cost(totalKnownCostUsd)}</p>
              <p className="mt-1 text-xs text-muted-foreground">另有 {unknownCostCalls} 次调用成本未知</p>
            </article>
            <article className="rounded-xl border p-5"><h2 className="text-sm text-muted-foreground">提示缓存命中率</h2><p className="mt-2 text-2xl font-bold">{percent(cacheReadRatio)}</p></article>
            <article className="rounded-xl border p-5"><h2 className="text-sm text-muted-foreground">批处理占比</h2><p className="mt-2 text-2xl font-bold">{percent(batchShare)}</p></article>
            <article className="rounded-xl border p-5">
              <h2 className="text-sm text-muted-foreground">熔断器状态</h2>
              {summary.circuitBreakers.length ? (
                <ul className="mt-2 space-y-1">
                  {summary.circuitBreakers.map((breaker) => (
                    <li key={breaker.name} className="flex justify-between gap-3 text-sm">
                      <span>{breaker.name}</span><span className="font-semibold">{breaker.state}</span>
                    </li>
                  ))}
                </ul>
              ) : <p className="mt-2 text-sm text-muted-foreground">暂无熔断器状态。</p>}
            </article>
          </div>
          <section aria-labelledby="crawl-title" className="space-y-4 rounded-xl border p-5">
            <div className="space-y-1">
              <h2 id="crawl-title" className="text-xl font-semibold">官方知识源抓取</h2>
              <p className="text-sm text-muted-foreground">仅能抓取目录中的官方来源。每次最多选择 3 个来源，并填写审批记录编号。</p>
            </div>
            <form className="space-y-4" onSubmit={(event) => void submitCrawl(event)}>
              {catalog.length ? (
                <fieldset className="grid gap-3 sm:grid-cols-2">
                  <legend className="mb-2 text-sm font-medium">选择抓取来源（最多 3 个）</legend>
                  {catalog.map((item) => {
                    const checked = selectedKeys.includes(item.sourceKey);
                    return (
                      <label key={item.sourceKey} className="flex items-start gap-2 rounded-lg border p-3 text-sm">
                        <input type="checkbox" className="mt-1" checked={checked}
                          disabled={!checked && selectedKeys.length >= 3}
                          onChange={(event) => setSelectedKeys((previous) => event.target.checked
                            ? [...previous, item.sourceKey] : previous.filter((key) => key !== item.sourceKey))} />
                        <span className="min-w-0">
                          <span className="block font-medium">{item.title}</span>
                          <span className="block text-muted-foreground">{item.springModule} · Spring {item.version} · {item.licenseType}</span>
                          <span className="block break-all text-xs text-muted-foreground">{item.sourceUri}</span>
                        </span>
                      </label>
                    );
                  })}
                </fieldset>
              ) : <p className="text-sm text-muted-foreground">目录中暂无启用的知识源。</p>}
              <label className="block max-w-xl space-y-2 text-sm">
                <span>审批记录编号</span>
                <input className="w-full rounded-md border bg-background px-3 py-2" required maxLength={500}
                  value={approvalReference} onChange={(event) => setApprovalReference(event.target.value)}
                  placeholder="填写获批的来源抓取记录" />
              </label>
              <button type="submit" disabled={crawling || !selectedKeys.length || !approvalReference.trim()}
                className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground disabled:cursor-not-allowed disabled:opacity-50">
                {crawling ? "正在抓取…" : "开始抓取"}
              </button>
            </form>
            {crawlError && <p role="alert" className="text-destructive">{crawlError}</p>}
            {crawlResult && (
              <div role="status" className="space-y-2 rounded-lg bg-muted p-4 text-sm">
                <p>已处理 {crawlResult.processedSources} 个来源</p>
                <ul className="space-y-1">
                  {crawlResult.results.map((result) => (
                    <li key={result.sourceKey}>{result.sourceKey}：{result.status}（{result.chunkCount} 个知识片段）</li>
                  ))}
                </ul>
              </div>
            )}
          </section>
        </>
      )}
    </section>
  );
}
