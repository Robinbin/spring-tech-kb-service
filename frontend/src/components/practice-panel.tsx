"use client";

import { useEffect, useMemo, useState } from "react";
import CodeMirror from "@uiw/react-codemirror";
import { java } from "@codemirror/lang-java";
import { javascript } from "@codemirror/lang-javascript";
import { python } from "@codemirror/lang-python";
import { oneDark } from "@codemirror/theme-one-dark";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { apiFetch } from "@/lib/client-api";
import type { DiagnosticQuestion, PracticeRunResult } from "@/lib/learning-types";

const languageMap = {
  java: java(),
  javascript: javascript(),
  typescript: javascript({ typescript: true }),
  python: python(),
};

function normalizeLanguage(value: string | null) {
  return (value ?? "java").toLowerCase();
}

export function PracticePanel() {
  const [question, setQuestion] = useState<DiagnosticQuestion | null>(null);
  const [assessmentId, setAssessmentId] = useState("");
  const [code, setCode] = useState("");
  const [result, setResult] = useState<PracticeRunResult | null>(null);
  const [busy, setBusy] = useState<"" | "run" | "submit">("");
  const [error, setError] = useState("");
  const language = normalizeLanguage(question?.language ?? "java");
  const extension = useMemo(() => languageMap[language as keyof typeof languageMap] ?? java(), [language]);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const aid = params.get("assessmentId");
    const qid = params.get("questionId");
    if (!aid || !qid) {
      setError("缺少 assessmentId 或 questionId，无法加载编程题。");
      return;
    }
    setAssessmentId(aid);
    const controller = new AbortController();
    apiFetch(`/api/backend/assessments/${encodeURIComponent(aid)}`, { signal: controller.signal })
      .then((response) => response.json())
      .then((assessment) => {
        const target = (assessment.questions ?? []).find((item: DiagnosticQuestion) => item.id === qid);
        if (!target) throw new Error("未找到指定编程题。");
        if (target.type !== "CODING") throw new Error("该题不是编程题。");
        setQuestion(target);
        setCode(target.userAnswer ?? target.starterCode ?? "");
      })
      .catch((reason: unknown) => {
        if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : "编程题加载失败。");
      });
    return () => controller.abort();
  }, []);

  async function run() {
    if (!question || !assessmentId || busy) return;
    setBusy("run");
    setError("");
    try {
      const response = await apiFetch(`/api/backend/assessments/${assessmentId}/coding/run`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ questionId: question.id, code, language }),
      });
      const payload: PracticeRunResult = await response.json();
      setResult(payload);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "运行失败。");
    } finally {
      setBusy("");
    }
  }

  async function submit() {
    if (!question || !assessmentId || busy) return;
    setBusy("submit");
    setError("");
    try {
      await apiFetch(`/api/backend/assessments/${assessmentId}/coding/submit`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ questionId: question.id, code, language }),
      });
      window.location.assign(`/assessment?id=${encodeURIComponent(assessmentId)}`);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "提交失败。");
    } finally {
      setBusy("");
    }
  }

  return <section className="space-y-6">
    <div>
      <h1 className="text-3xl font-bold">编程练习</h1>
      <p className="mt-2 text-muted-foreground">代码仅作为文本提交到后端判题，不会在浏览器执行。</p>
    </div>
    {error && <p role="alert" className="text-destructive">{error}</p>}
    {question && <>
      <article className="space-y-3 rounded-xl border p-4">
        <div className="flex flex-wrap items-center gap-2">
          <Badge>编程题</Badge>
          <Badge variant="outline">{language}</Badge>
          <Badge variant="secondary">{question.userAnswer ? "已保存草稿" : "未作答"}</Badge>
        </div>
        <h2 className="text-xl font-semibold">{question.content}</h2>
        <CodeMirror
          value={code}
          minHeight="320px"
          maxHeight="560px"
          extensions={[extension]}
          theme={oneDark}
          onChange={(value) => setCode(value)}
          basicSetup={{ lineNumbers: true, foldGutter: true, highlightActiveLine: true }}
        />
        <div className="flex flex-wrap gap-3">
          <Button type="button" onClick={() => void run()} disabled={busy !== "" || !code.trim()}>运行检查</Button>
          <Button type="button" variant="outline" onClick={() => void submit()} disabled={busy !== "" || !code.trim()}>提交答案</Button>
        </div>
      </article>
      {busy && <p role="status">{busy === "run" ? "正在运行检查…" : "正在提交答案…"} </p>}
      {result && <section className="grid gap-4 md:grid-cols-2">
        {[
          { title: "编译信息", lines: result.compile },
          { title: "运行信息", lines: result.runtime },
          { title: "测试反馈", lines: result.tests },
          { title: "静态分析提示", lines: result.staticHints },
        ].map(({ title, lines }) => <article key={title} className="space-y-2 rounded-xl border p-4">
          <h3 className="font-semibold">{title}</h3>
          {!!lines.length ? <ul className="list-disc space-y-1 pl-4 text-sm">
            {lines.map((line) => <li key={line}>{line}</li>)}
          </ul> : <p className="text-sm text-muted-foreground">暂无。</p>}
        </article>)}
        <article className="space-y-2 rounded-xl border p-4 md:col-span-2">
          <h3 className="font-semibold">程序输出</h3>
          <pre className="overflow-x-auto whitespace-pre-wrap rounded-lg bg-secondary p-3 text-sm">{result.output || "无输出"}</pre>
        </article>
      </section>}
    </>}
  </section>;
}
