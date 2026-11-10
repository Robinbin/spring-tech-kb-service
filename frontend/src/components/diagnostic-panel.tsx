"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { TopicPicker, type Topic } from "@/components/topic-picker";
import { SourceList } from "@/components/source-list";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { apiFetch } from "@/lib/client-api";
import type { Diagnostic } from "@/lib/learning-types";

export function DiagnosticPanel() {
  const [topics, setTopics] = useState<Topic[]>([]);
  const [selected, setSelected] = useState<string[]>([]);
  const [assessment, setAssessment] = useState<Diagnostic | null>(null);
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    const controller = new AbortController();
    const options = { signal: controller.signal };
    const id = new URLSearchParams(window.location.search).get("id");
    Promise.all([
      apiFetch("/api/backend/knowledge/topics", options).then((response) => response.json()),
      id ? apiFetch(`/api/backend/assessments/${encodeURIComponent(id)}`, options).then((response) => response.json()) : Promise.resolve(null),
    ]).then(([catalog, session]: [Topic[], Diagnostic | null]) => { setTopics(catalog); setAssessment(session); })
      .catch((reason: unknown) => { if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : "摸底数据加载失败。"); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, []);

  async function begin(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selected.length || busy) return;
    setBusy(true); setError("");
    try {
      const response = await apiFetch("/api/backend/assessments/initial", {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ topicIds: selected }),
      });
      const session: Diagnostic = await response.json();
      setAssessment(session); setAnswers({});
      window.history.replaceState(null, "", `/assessment?id=${encodeURIComponent(session.id)}`);
    } catch (reason) { setError(reason instanceof Error ? reason.message : "摸底启动失败。"); }
    finally { setBusy(false); }
  }

  async function answer(event: React.FormEvent<HTMLFormElement>, questionId: string) {
    event.preventDefault();
    if (!assessment || !answers[questionId] || busy) return;
    setBusy(true); setError("");
    try {
      await apiFetch(`/api/backend/assessments/${assessment.id}/answers`, {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ questionId, answer: answers[questionId] }),
      });
      const response = await apiFetch(`/api/backend/assessments/${assessment.id}`);
      setAssessment(await response.json());
    } catch (reason) { setError(reason instanceof Error ? reason.message : "答案提交失败。"); }
    finally { setBusy(false); }
  }

  return <section className="space-y-6">
    <div><h1 className="text-3xl font-bold">初始摸底评估</h1>
      <p className="mt-2 text-muted-foreground">只使用人工审核过的选择题。提交结果会更新你的技能档案，并用于后续学习计划；每题只能计分一次。</p></div>
    <Link href="/plans" className="inline-block underline">返回学习计划</Link>
    {loading && <p role="status">正在加载摸底数据…</p>}
    {error && <p role="alert" className="text-destructive">{error}</p>}
    {!assessment && <form className="space-y-4" onSubmit={begin}>
      <TopicPicker topics={topics} selected={selected} onChange={setSelected} />
      <p className="text-sm text-muted-foreground">无可用题目时不会生成假题。请由管理员通过题目录入 API 配置审核题库。</p>
      <Button type="submit" disabled={loading || busy || !selected.length || selected.length > 50}>开始摸底</Button>
    </form>}
    {assessment && <>
      <div className="flex flex-wrap items-center gap-3"><Badge>{assessment.status === "COMPLETED" ? "已完成" : "答题中"}</Badge>
        <p>已答 {assessment.questions.filter((question) => question.userAnswer !== null).length} / {assessment.totalQuestions} 题</p></div>
      {assessment.status === "COMPLETED" && <div role="status" className="space-y-2 rounded-xl border p-4">
        <h2 className="text-xl font-semibold">摸底完成：{assessment.score} 分</h2>
        <p>答对 {assessment.correctCount} / {assessment.totalQuestions} 题，技能档案已更新。</p>
        <div className="flex flex-wrap gap-4">
          <Link className="underline" href="/plans">根据摸底结果生成学习计划</Link>
          <Link className="underline" href={`/report?id=${encodeURIComponent(assessment.id)}`}>查看能力报告</Link>
        </div>
      </div>}
      {assessment.questions.map((question, index) => <form key={question.id} className="space-y-4 rounded-xl border p-4"
        onSubmit={(event) => void answer(event, question.id)}>
        <fieldset className="space-y-3" disabled={busy || question.userAnswer !== null || assessment.status !== "IN_PROGRESS"}>
          <legend className="mb-3 text-lg font-semibold">{index + 1}. {question.content}</legend>
          {question.type === "MCQ" && question.options.map((option) => <label key={option.label} className="flex items-start gap-2 text-sm">
            <input type="radio" name={`question-${question.id}`} className="mt-1" value={option.label}
              checked={(question.userAnswer ?? answers[question.id]) === option.label}
              onChange={() => setAnswers({ ...answers, [question.id]: option.label })} />
            <span>{option.label}. {option.text}</span>
          </label>)}
          {question.type === "OPEN_ENDED" && <textarea
            className="min-h-28 w-full rounded-lg border bg-background p-3 text-sm"
            value={question.userAnswer ?? answers[question.id] ?? ""}
            onChange={(event) => setAnswers({ ...answers, [question.id]: event.target.value })}
            maxLength={4000}
            placeholder="请输入你的分析或方案。"
          />}
          {question.type === "CODING" && <div className="space-y-2 rounded-lg border border-dashed p-3 text-sm">
            <p>请前往编程练习页完成作答，支持运行检查后再提交。</p>
            <Link className="underline" href={`/practice?assessmentId=${encodeURIComponent(assessment.id)}&questionId=${encodeURIComponent(question.id)}`}>
              打开 CodeMirror 编程编辑器
            </Link>
          </div>}
        </fieldset>
        {question.type !== "CODING" && (question.userAnswer === null
          ? <Button type="submit" disabled={busy || !answers[question.id]?.trim()}>提交本题</Button>
          : <p className="text-sm">已提交：{question.isCorrect === null ? "已记录主观答案，待评估" : question.isCorrect ? "回答正确" : "回答不正确"}</p>)}
        {question.type === "CODING" && question.userAnswer && <p className="text-sm">已提交：编程答案已记录。</p>}
        {assessment.status === "COMPLETED" && <div className="space-y-1 text-sm">
          {question.correctAnswer && <p>正确答案：{question.correctAnswer}</p>}
          {question.explanation && <p className="whitespace-pre-wrap">{question.explanation}</p>}
        </div>}
        <SourceList sources={[question.source]} />
      </form>)}
    </>}
    {busy && <p role="status">正在提交，请稍候…</p>}
  </section>;
}
