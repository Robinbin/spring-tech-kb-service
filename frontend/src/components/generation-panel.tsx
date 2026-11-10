"use client";

import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Badge } from "@/components/ui/badge";
import { SourceList, type AttributedSource } from "@/components/source-list";
import { apiFetch } from "@/lib/client-api";

type GeneratedOutput = {
  id: string;
  kind: "EXAMPLE" | "COMPARISON" | "PROJECT";
  title: string;
  content: string;
  code: string;
  language: string;
  aiGenerated: boolean;
  origin: string;
  sources: AttributedSource[];
  modelId: string;
  promptVersion: string;
  level: string | null;
  springVersion: string | null;
};

export function GenerationPanel() {
  const [result, setResult] = useState<GeneratedOutput | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [module, setModule] = useState("");
  const [version, setVersion] = useState("");

  useEffect(() => {
    const id = new URLSearchParams(window.location.search).get("id");
    if (!id) return;
    const controller = new AbortController();
    apiFetch(`/api/backend/generation/${encodeURIComponent(id)}`, { signal: controller.signal })
      .then((response) => response.json()).then(setResult)
      .catch((reason: unknown) => { if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : "内容加载失败。"); });
    return () => controller.abort();
  }, []);

  async function generate(event: React.FormEvent<HTMLFormElement>, kind: "examples" | "comparisons") {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setBusy(true); setError(""); setNotice("");
    try {
      const input = kind === "examples"
        ? { query: form.get("query"), level: form.get("level") }
        : { left: form.get("left"), right: form.get("right") };
      const response = await apiFetch(`/api/backend/generation/${kind}`, {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ...input, module: module || null, springVersion: version || null }),
      });
      const output: GeneratedOutput = await response.json();
      setResult(output);
      window.history.replaceState(null, "", `/examples?id=${encodeURIComponent(output.id)}`);
    } catch (reason) { setError(reason instanceof Error ? reason.message : "生成失败。"); }
    finally { setBusy(false); }
  }

  async function download(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setBusy(true); setError(""); setNotice("");
    try {
      const response = await apiFetch("/api/backend/generation/projects", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          groupId: form.get("groupId"), artifactId: form.get("artifactId"), name: form.get("name"),
          generationId: form.get("includeExample") && result?.kind === "EXAMPLE" ? result.id : null,
        }),
      });
      const url = URL.createObjectURL(await response.blob());
      const link = document.createElement("a");
      link.href = url; link.download = `${String(form.get("artifactId"))}.zip`;
      link.click(); URL.revokeObjectURL(url);
      setNotice("项目骨架已准备下载。示例仅附作文档，不会自动执行。");
    } catch (reason) { setError(reason instanceof Error ? reason.message : "项目下载失败。"); }
    finally { setBusy(false); }
  }

  return <section className="space-y-6">
    <div><h1 className="text-3xl font-bold">代码示例与技术对比</h1>
      <p className="mt-2 text-muted-foreground">基于已审核知识生成，AI 内容请人工核实；不会自动执行或写入正式知识库。</p></div>
    <div className="grid gap-4 sm:grid-cols-2">
      <div className="space-y-2"><Label htmlFor="generation-module">模块筛选</Label><Input id="generation-module" value={module} onChange={(e) => setModule(e.target.value)} maxLength={100} /></div>
      <div className="space-y-2"><Label htmlFor="generation-version">Spring 版本筛选</Label><Input id="generation-version" value={version} onChange={(e) => setVersion(e.target.value)} maxLength={50} /></div>
    </div>
    <div className="grid gap-5 lg:grid-cols-2">
      <form className="space-y-4 rounded-xl border p-4" onSubmit={(event) => void generate(event, "examples")}>
        <h2 className="text-xl font-semibold">三级代码示例</h2>
        <div className="space-y-2"><Label htmlFor="example-query">示例需求</Label>
          <Input id="example-query" name="query" placeholder="例如：Bean 生命周期回调" required maxLength={2000} /></div>
        <div className="space-y-2"><Label htmlFor="example-level">示例层级</Label>
          <select id="example-level" name="level" className="w-full rounded-lg border bg-background p-2 text-sm" defaultValue="CONCEPT">
            <option value="CONCEPT">概念级</option><option value="PRACTICAL">实战级</option><option value="SOURCE">源码级</option>
          </select></div>
        <Button type="submit" disabled={busy}>生成代码示例</Button>
      </form>
      <form className="space-y-4 rounded-xl border p-4" onSubmit={(event) => void generate(event, "comparisons")}>
        <h2 className="text-xl font-semibold">技术对比文档</h2>
        <div className="space-y-2"><Label htmlFor="compare-left">对比对象 A</Label><Input id="compare-left" name="left" required maxLength={500} placeholder="例如：BeanFactory" /></div>
        <div className="space-y-2"><Label htmlFor="compare-right">对比对象 B</Label><Input id="compare-right" name="right" required maxLength={500} placeholder="例如：ApplicationContext" /></div>
        <p className="text-xs text-muted-foreground">API 版本对比可使用相同全限定类名加 @版本，例如 org.example.Bean@6.1 与 org.example.Bean@6.2。</p>
        <Button type="submit" disabled={busy}>生成技术对比</Button>
      </form>
    </div>
    {busy && <p role="status">正在处理，请勿重复提交…</p>}
    {error && <p role="alert" className="text-destructive">{error}</p>}
    {notice && <p role="status">{notice}</p>}
    {result && <article className="space-y-4 rounded-xl border p-5">
      <div className="flex flex-wrap gap-2"><Badge>AI 生成内容</Badge><Badge variant="outline">{result.kind}</Badge>
        {result.level && <Badge variant="secondary">{result.level}</Badge>}
        {result.springVersion && <Badge variant="outline">{result.springVersion}</Badge>}</div>
      <h2 className="text-2xl font-semibold">{result.title}</h2>
      <p className="whitespace-pre-wrap leading-7">{result.content}</p>
      {result.code && <div><p className="mb-2 text-sm text-muted-foreground">{result.language}</p><pre className="rounded-lg bg-secondary p-4 text-sm"><code>{result.code}</code></pre></div>}
      <p className="text-xs text-muted-foreground">模型：{result.modelId} · Prompt：{result.promptVersion}</p>
      <SourceList sources={result.sources} />
    </article>}
    <form className="space-y-4 rounded-xl border p-4" onSubmit={(event) => void download(event)}>
      <h2 className="text-xl font-semibold">下载 Spring Boot 项目骨架</h2>
      <p className="text-sm text-muted-foreground">固定 Java 21 / Spring Boot 4.1.2 模板，包含源码、测试和 Maven 配置。</p>
      <div className="grid gap-4 sm:grid-cols-3">
        <div className="space-y-2"><Label htmlFor="project-group">Group ID</Label><Input id="project-group" name="groupId" defaultValue="com.example" required maxLength={120} /></div>
        <div className="space-y-2"><Label htmlFor="project-artifact">Artifact ID</Label><Input id="project-artifact" name="artifactId" defaultValue="spring-demo" required maxLength={64} /></div>
        <div className="space-y-2"><Label htmlFor="project-name">项目名称</Label><Input id="project-name" name="name" defaultValue="Spring Demo" required maxLength={100} /></div>
      </div>
      {result?.kind === "EXAMPLE" && <label className="flex items-center gap-2 text-sm"><input name="includeExample" type="checkbox" />将当前示例及来源附作文档</label>}
      <Button type="submit" variant="outline" disabled={busy}>下载项目 ZIP</Button>
    </form>
  </section>;
}
