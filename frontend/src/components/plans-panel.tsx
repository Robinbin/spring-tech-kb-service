"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { TopicPicker, type Topic } from "@/components/topic-picker";
import { SourceList } from "@/components/source-list";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Badge } from "@/components/ui/badge";
import { apiFetch } from "@/lib/client-api";
import type { LearningPlan, PlanSummary } from "@/lib/learning-types";

export function PlansPanel() {
  const [topics, setTopics] = useState<Topic[]>([]);
  const [selected, setSelected] = useState<string[]>([]);
  const [plans, setPlans] = useState<PlanSummary[]>([]);
  const [goal, setGoal] = useState("");
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => {
    const controller = new AbortController();
    Promise.all([
      apiFetch("/api/backend/knowledge/topics", { signal: controller.signal }).then((response) => response.json()),
      apiFetch("/api/backend/learning/plans", { signal: controller.signal }).then((response) => response.json()),
    ])
      .then(([catalog, history]: [Topic[], PlanSummary[]]) => { setTopics(catalog); setPlans(history); })
      .catch((reason: unknown) => { if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : "学习数据加载失败。"); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, []);

  async function create(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selected.length || busy) return;
    setBusy(true); setError("");
    try {
      const response = await apiFetch("/api/backend/learning/plans", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ topicIds: selected, goal: goal || null }),
      });
      const plan: LearningPlan = await response.json();
      window.location.assign(`/plans/${encodeURIComponent(plan.id)}`);
    } catch (reason) { setError(reason instanceof Error ? reason.message : "计划生成失败。"); setBusy(false); }
  }

  return <section className="space-y-6">
    <div><h1 className="text-3xl font-bold">学习计划</h1>
      <p className="mt-2 text-muted-foreground">结合你的技能状态和知识前置关系生成独立计划，个性化内容不会进入共享语义缓存。</p></div>
    <Link href="/assessment" className="inline-block underline">先做初始摸底评估</Link>
    {loading && <p role="status">正在加载主题和计划…</p>}
    {error && <p role="alert" className="text-destructive">{error}</p>}
    <form className="space-y-4" onSubmit={create}>
      <TopicPicker topics={topics} selected={selected} onChange={setSelected} />
      <div className="space-y-2"><Label htmlFor="learning-goal">学习目标（可选）</Label>
        <textarea id="learning-goal" className="min-h-24 w-full rounded-lg border bg-background p-3 text-sm"
          value={goal} onChange={(event) => setGoal(event.target.value)} maxLength={2000}
          placeholder="例如：基于 Spring 6.1 掌握容器扩展点，每周学习 3 小时。" /></div>
      <Button type="submit" disabled={loading || busy || selected.length < 1 || selected.length > 50}>生成学习计划</Button>
      {busy && <p role="status">正在生成并校验计划的前置依赖，请稍候…</p>}
    </form>
    <div className="space-y-4"><h2 className="text-2xl font-semibold">我的计划</h2>
      {!loading && !plans.length && <p className="text-muted-foreground">还没有计划，选择主题后开始生成。</p>}
      {plans.map((plan) => <article key={plan.id} className="space-y-3 rounded-xl border p-4">
        <h3 className="text-xl font-semibold"><Link href={`/plans/${encodeURIComponent(plan.id)}`} className="underline">{plan.title}</Link></h3>
        <Badge variant="outline">{plan.status}</Badge>
        {plan.goal && <p className="whitespace-pre-wrap">{plan.goal}</p>}
        <p className="text-sm">已完成 {plan.completedTaskCount} / {plan.taskCount} 个任务</p>
        <SourceList sources={plan.sources} />
      </article>)}
    </div>
  </section>;
}
