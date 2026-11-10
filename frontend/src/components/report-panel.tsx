"use client";

import { useEffect, useMemo, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { apiFetch } from "@/lib/client-api";
import type { AbilityReport } from "@/lib/learning-types";

const size = 280;
const cx = size / 2;
const cy = size / 2;
const levels = 5;

export function ReportPanel() {
  const [report, setReport] = useState<AbilityReport | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const id = new URLSearchParams(window.location.search).get("id");
    if (!id) { setError("缺少评估 ID。"); setLoading(false); return; }
    const controller = new AbortController();
    apiFetch(`/api/backend/assessments/${encodeURIComponent(id)}/report`, { signal: controller.signal })
      .then((response) => response.json())
      .then(setReport)
      .catch((reason: unknown) => {
        if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : "能力报告加载失败。");
      })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, []);

  const chart = useMemo(() => {
    if (!report || !report.levels.length) return null;
    const radius = size * 0.38;
    const angleStep = (Math.PI * 2) / report.levels.length;
    const point = (index: number, value: number, scale = 100) => {
      const angle = -Math.PI / 2 + index * angleStep;
      const r = radius * (value / scale);
      return `${cx + Math.cos(angle) * r},${cy + Math.sin(angle) * r}`;
    };
    return {
      rings: Array.from({ length: levels }, (_, index) => {
        const percent = (index + 1) * (100 / levels);
        return report.levels.map((_, axis) => point(axis, percent)).join(" ");
      }),
      axes: report.levels.map((_, axis) => point(axis, 100)),
      labels: report.levels.map((item, axis) => ({ ...item, p: point(axis, 112) })),
      profile: report.levels.map((item, axis) => point(axis, item.value)).join(" "),
    };
  }, [report]);

  return <section className="space-y-6">
    <div>
      <h1 className="text-3xl font-bold">能力报告</h1>
      <p className="mt-2 text-muted-foreground">基于当前评估会话生成。该图为确定性 SVG 渲染，便于回归测试。</p>
    </div>
    {loading && <p role="status">正在加载报告…</p>}
    {error && <p role="alert" className="text-destructive">{error}</p>}
    {report && chart && <article className="space-y-4 rounded-xl border p-5">
      <div className="flex flex-wrap items-center gap-3">
        <Badge>总分 {report.overallScore}</Badge>
        <Badge variant="outline">评估会话：{report.assessmentId}</Badge>
      </div>
      <svg role="img" aria-label="能力雷达图" viewBox={`0 0 ${size} ${size}`} className="h-[320px] w-full max-w-[360px]">
        {chart.rings.map((ring) => <polygon key={ring} points={ring} fill="none" stroke="currentColor" strokeOpacity="0.15" />)}
        {chart.axes.map((endpoint, index) => <line key={report.levels[index].label} x1={cx} y1={cy}
          x2={endpoint.split(",")[0]} y2={endpoint.split(",")[1]} stroke="currentColor" strokeOpacity="0.25" />)}
        <polygon data-testid="radar-profile" points={chart.profile} fill="currentColor" fillOpacity="0.2" stroke="currentColor" />
        {chart.labels.map((label) => <text key={label.label} x={label.p.split(",")[0]} y={label.p.split(",")[1]}
          textAnchor="middle" className="fill-foreground text-[10px]">{label.label}({label.value})</text>)}
      </svg>
      <div className="grid gap-4 md:grid-cols-2">
        <section><h2 className="mb-2 font-semibold">优势</h2>
          <ul className="list-disc space-y-1 pl-4 text-sm">{report.strengths.map((item) => <li key={item}>{item}</li>)}</ul>
        </section>
        <section><h2 className="mb-2 font-semibold">待提升</h2>
          <ul className="list-disc space-y-1 pl-4 text-sm">{report.improvements.map((item) => <li key={item}>{item}</li>)}</ul>
        </section>
      </div>
      <p className="text-xs text-muted-foreground">生成时间：{new Date(report.generatedAt).toLocaleString("zh-CN")}</p>
    </article>}
  </section>;
}
