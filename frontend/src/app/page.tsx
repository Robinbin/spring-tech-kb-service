import Link from "next/link";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

export default function Home() {
  return (
    <section className="space-y-8 py-8">
      <div className="max-w-2xl space-y-4">
        <p className="text-sm font-semibold text-muted-foreground">Spring 工程知识库</p>
        <h1 className="text-4xl font-bold tracking-tight">找到依据，再深入理解。</h1>
        <p className="text-lg text-muted-foreground">检索 Spring 官方知识，按版本核对适用范围，以流式问答连接知识与源码。</p>
      </div>
      <div className="grid gap-4 sm:grid-cols-2">
        <Card><CardHeader><CardTitle>知识检索</CardTitle></CardHeader><CardContent className="space-y-4">
          <p>通过关键词、模块与版本查找知识，查看原文和许可。</p>
          <Link className="font-semibold underline" href="/search">开始搜索</Link>
        </CardContent></Card>
        <Card><CardHeader><CardTitle>个性化学习</CardTitle></CardHeader><CardContent className="space-y-4">
          <p>从已审核题目摸底，生成满足知识前置关系的计划，并追踪任务进度。</p>
          <Link className="font-semibold underline" href="/plans">制定学习计划</Link>
        </CardContent></Card>
        <Card><CardHeader><CardTitle>代码与技术对比</CardTitle></CardHeader><CardContent className="space-y-4">
          <p>生成附来源的三级代码示例、技术对比，并下载 Spring Boot 项目骨架。</p>
          <Link className="font-semibold underline" href="/examples">查看生成工具</Link>
        </CardContent></Card>
        <Card><CardHeader><CardTitle>引用式问答</CardTitle></CardHeader><CardContent className="space-y-4">
          <p>提出工程问题，逐步阅读回答并跳转到引用原文。</p>
          <Link className="font-semibold underline" href="/chat">打开问答</Link>
        </CardContent></Card>
      </div>
    </section>
  );
}
