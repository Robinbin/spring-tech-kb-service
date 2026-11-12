"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { SourceList } from "@/components/source-list";
import { apiFetch } from "@/lib/client-api";
import type { LearningPlan, LearningTask, TaskStatus } from "@/lib/learning-types";

const labels: Record<TaskStatus, string> = { PENDING: "待开始", IN_PROGRESS: "进行中", COMPLETED: "已完成", SKIPPED: "已跳过" };

export function TaskBoard({ id }: { id: string }) {
  const [plan, setPlan] = useState<LearningPlan | null>(null);
  const [times, setTimes] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  useEffect(() => {
    const controller = new AbortController();
    apiFetch(`/api/backend/learning/plans/${encodeURIComponent(id)}`, { signal: controller.signal })
      .then((response) => response.json()).then(setPlan)
      .catch((reason: unknown) => { if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : "计划加载失败。"); });
    return () => controller.abort();
  }, [id]);

  async function update(task: LearningTask, status: TaskStatus) {
    const seconds = Number(times[task.id] ?? task.timeSpentSeconds);
    if (!Number.isInteger(seconds) || seconds < 0 || seconds > 86400) { setError("累计用时须为 0 至 86400 的整数秒。"); return; }
    setBusy(true); setError(""); setNotice("");
    try {
      await apiFetch(`/api/backend/learning/tasks/${task.id}`, {
        method: "PATCH", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ status, timeSpentSeconds: seconds }),
      });
      const response = await apiFetch(`/api/backend/learning/plans/${encodeURIComponent(id)}`);
      setPlan(await response.json());
    } catch (reason) { setError(reason instanceof Error ? reason.message : "任务更新失败。"); }
    finally { setBusy(false); }
  }

  async function adapt() {
    setBusy(true); setError(""); setNotice("");
    try {
      const response = await apiFetch(`/api/backend/learning/plans/${encodeURIComponent(id)}/adapt`, {
        method: "POST", headers: { "Content-Type": "application/json" }, body: "{}",
      });
      const updated: LearningPlan = await response.json();
      const changed = updated.tasks.length !== plan?.tasks.length;
      setPlan(updated);
      setNotice(changed ? "已根据完成率增加针对性复习任务；原有已完成任务保持不变。" : "当前完成率无需调整，或本轮复习任务已存在。");
    } catch (reason) { setError(reason instanceof Error ? reason.message : "计划调整失败。"); }
    finally { setBusy(false); }
  }

  return <section className="space-y-6">
    <Link href="/plans" className="text-sm underline">返回学习计划</Link>
    {error && <p role="alert" className="text-destructive">{error}</p>}
    {!plan ? !error && <p role="status">正在加载任务看板…</p> : <>
      <div><h1 className="text-3xl font-bold">{plan.title}</h1>
        <p className="mt-2 whitespace-pre-wrap text-muted-foreground">{plan.goal}</p></div>
      <div className="flex flex-wrap items-center gap-3"><Badge>{plan.status}</Badge>
        <p>已完成 {plan.tasks.filter((task) => task.status === "COMPLETED").length} / {plan.tasks.length} 个任务</p>
        <Button type="button" variant="outline" disabled={busy || plan.status !== "ACTIVE"} onClick={() => void adapt()}>根据完成率调整计划</Button></div>
      {notice && <p role="status">{notice}</p>}
      <div className="grid gap-4 lg:grid-cols-2">
        {plan.tasks.map((task, index) => {
          const waiting = task.prerequisiteTaskIds.map((prerequisite) => plan.tasks.find((candidate) => candidate.id === prerequisite))
            .filter((prerequisite) => !prerequisite || prerequisite.status !== "COMPLETED");
          return <article key={task.id} className="space-y-3 rounded-xl border p-4" aria-label={`任务：${task.title}`}>
            <div className="flex flex-wrap gap-2"><Badge variant={task.status === "COMPLETED" ? "default" : "outline"}>{labels[task.status]}</Badge>
              {task.targetVersion && <Badge variant="secondary">{task.targetVersion}</Badge>}</div>
            <h2 className="text-xl font-semibold">{index + 1}. {task.title}</h2>
            <p className="whitespace-pre-wrap text-sm">{task.description}</p>
            {!!task.prerequisiteTaskIds.length && <p className="text-sm text-muted-foreground">前置任务：{task.prerequisiteTaskIds.map((prerequisite) => plan.tasks.find((candidate) => candidate.id === prerequisite)?.title ?? "未知任务").join("、")}</p>}
            {!!waiting.length && task.status !== "COMPLETED" && <p className="text-sm text-muted-foreground">须先完成前置任务，才能开始或完成本任务。</p>}
            <div className="space-y-2"><Label htmlFor={`time-${task.id}`}>累计用时（秒）</Label>
              <Input id={`time-${task.id}`} type="number" min={0} max={86400} step={1} value={times[task.id] ?? task.timeSpentSeconds}
                disabled={busy || task.status === "COMPLETED"} onChange={(event) => setTimes({ ...times, [task.id]: event.target.value })} /></div>
            <div className="flex flex-wrap gap-2">
              {task.status === "PENDING" && <Button disabled={busy || !!waiting.length} onClick={() => void update(task, "IN_PROGRESS")}>开始任务</Button>}
              {["PENDING", "IN_PROGRESS"].includes(task.status) && <>
                <Button disabled={busy || !!waiting.length} onClick={() => void update(task, "COMPLETED")}>完成任务</Button>
                <Button variant="outline" disabled={busy} onClick={() => void update(task, "SKIPPED")}>跳过任务</Button>
              </>}
              {task.status === "SKIPPED" && <Button variant="outline" disabled={busy} onClick={() => void update(task, "PENDING")}>恢复待开始</Button>}
              {task.status === "COMPLETED" && <p className="text-sm text-muted-foreground">已完成任务不可修改。</p>}
            </div>
          </article>;
        })}
      </div>
      <p className="text-xs text-muted-foreground">AI 生成计划 · 模型：{plan.modelId} · Prompt：{plan.promptVersion}</p>
      <SourceList sources={plan.sources} />
    </>}
  </section>;
}
