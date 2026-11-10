"use client";

import { forceCenter, forceCollide, forceLink, forceManyBody, forceSimulation, type SimulationLinkDatum, type SimulationNodeDatum } from "d3-force";
import { useEffect, useState } from "react";
import { apiFetch } from "@/lib/client-api";
import { Button } from "@/components/ui/button";

const MAX_NODES = 100;
const MAX_EDGES = 300;
const WIDTH = 800;
const HEIGHT = 520;

type Topic = {
  id: string;
  name: string;
  springModule: string;
  prerequisites: string[];
};
type GraphNode = SimulationNodeDatum & Pick<Topic, "id" | "name" | "springModule">;
type GraphEdge = { sourceId: string; targetId: string };
type SimulatedLink = SimulationLinkDatum<GraphNode>;
type Position = { x: number; y: number };

function isTopic(value: unknown): value is Topic {
  if (!value || typeof value !== "object") return false;
  const topic = value as Partial<Topic>;
  return typeof topic.id === "string" && typeof topic.name === "string"
    && typeof topic.springModule === "string" && Array.isArray(topic.prerequisites);
}

export function KnowledgeGraph() {
  const [topics, setTopics] = useState<Topic[]>([]);
  const [edges, setEdges] = useState<GraphEdge[]>([]);
  const [positions, setPositions] = useState<Map<string, Position>>(new Map());
  const [selectedId, setSelectedId] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    let active = true;
    async function load() {
      try {
        const response = await apiFetch("/api/backend/knowledge/topics");
        const payload: unknown = await response.json();
        if (!Array.isArray(payload)) throw new Error("主题目录格式无效。");
        const catalog = payload.filter(isTopic).slice(0, MAX_NODES);
        const knownIds = new Set(catalog.map((topic) => topic.id));

        // The topic catalog carries prerequisite IDs. Confirm them against the
        // read-only closure endpoint so the graph reflects the server's graph.
        const closures = await Promise.all(catalog.map(async (topic) => {
          const result = await apiFetch(`/api/backend/knowledge/topics/${topic.id}/prerequisites`);
          const items: unknown = await result.json();
          if (!Array.isArray(items)) throw new Error("前置主题数据格式无效。");
          return { id: topic.id, prerequisiteIds: items.filter(isTopic).map((item) => item.id) };
        }));
        const links = closures.flatMap(({ id, prerequisiteIds }) =>
          prerequisiteIds.filter((prerequisiteId) => prerequisiteId !== id && knownIds.has(prerequisiteId))
            .map((sourceId) => ({ sourceId, targetId: id })),
        ).slice(0, MAX_EDGES);
        if (!active) return;
        setTopics(catalog);
        setEdges(links);
        setSelectedId(catalog[0]?.id ?? "");
      } catch (reason) {
        if (active) setError(reason instanceof Error ? reason.message : "知识图谱加载失败。");
      } finally {
        if (active) setLoading(false);
      }
    }
    void load();
    return () => { active = false; };
  }, []);

  useEffect(() => {
    if (!topics.length) return;
    const nodes: GraphNode[] = topics.map(({ id, name, springModule }) => ({ id, name, springModule }));
    const links: SimulatedLink[] = edges.map(({ sourceId, targetId }) => ({ source: sourceId, target: targetId }));
    const simulation = forceSimulation(nodes)
      .force("link", forceLink<GraphNode, SimulatedLink>(links).id((node) => node.id).distance(100).strength(0.45))
      .force("charge", forceManyBody().strength(-250))
      .force("center", forceCenter(WIDTH / 2, HEIGHT / 2))
      .force("collide", forceCollide<GraphNode>(38))
      .on("tick", () => {
        setPositions(new Map(nodes.map((node) => [
          node.id,
          { x: Math.max(34, Math.min(WIDTH - 34, node.x ?? WIDTH / 2)), y: Math.max(30, Math.min(HEIGHT - 24, node.y ?? HEIGHT / 2)) },
        ])));
      });
    return () => { simulation.stop(); };
  }, [topics, edges]);

  const selectedTopic = topics.find((topic) => topic.id === selectedId);
  const selectedEdges = selectedTopic
    ? edges.filter((edge) => edge.targetId === selectedTopic.id)
    : [];

  return (
    <section className="space-y-5">
      <header className="space-y-2">
        <p className="text-sm text-muted-foreground">学习地图</p>
        <h1 className="text-3xl font-bold">知识前置关系图</h1>
        <p className="text-muted-foreground">点选主题查看其前置主题。连线方向为「前置主题 → 当前主题」。</p>
      </header>
      {loading && <p role="status">正在加载知识图谱…</p>}
      {error && <p role="alert" className="text-destructive">{error}</p>}
      {!loading && !error && !topics.length && (
        <p className="rounded-lg border p-6 text-muted-foreground">暂无可展示的知识主题。</p>
      )}
      {!loading && !error && topics.length > 0 && (
        <div className="grid gap-5 lg:grid-cols-[minmax(0,2fr)_minmax(15rem,1fr)]">
          <div className="min-w-0 rounded-xl border bg-card p-2 sm:p-4">
            <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} role="img" aria-label="知识前置关系图" className="h-auto max-h-[65vh] w-full">
              <title>知识前置关系图</title>
              <desc>可通过下方主题列表键盘操作并选择节点。连线从前置主题指向依赖它的主题。</desc>
              <defs>
                <marker id="graph-arrow" markerWidth="8" markerHeight="8" refX="7" refY="3" orient="auto">
                  <path d="M0,0 L0,6 L7,3 z" fill="currentColor" />
                </marker>
              </defs>
              {edges.map((edge) => {
                const from = positions.get(edge.sourceId);
                const to = positions.get(edge.targetId);
                if (!from || !to) return null;
                return <line key={`${edge.sourceId}-${edge.targetId}`} x1={from.x} y1={from.y} x2={to.x} y2={to.y}
                  stroke="currentColor" strokeOpacity=".35" strokeWidth="1.5" markerEnd="url(#graph-arrow)" />;
              })}
              {topics.map((topic) => {
                const point = positions.get(topic.id);
                if (!point) return null;
                const selected = topic.id === selectedId;
                return (
                  <g key={topic.id} transform={`translate(${point.x},${point.y})`} role="button" tabIndex={0}
                    aria-label={`选择主题：${topic.name}`} aria-pressed={selected}
                    onClick={() => setSelectedId(topic.id)}
                    onKeyDown={(event) => {
                      if (event.key === "Enter" || event.key === " ") {
                        event.preventDefault();
                        setSelectedId(topic.id);
                      }
                    }} className="cursor-pointer">
                    <circle r={selected ? 21 : 18} fill={selected ? "var(--primary)" : "var(--card)"}
                      stroke="var(--foreground)" strokeOpacity=".7" strokeWidth="1.5" />
                    <text y="34" textAnchor="middle" fontSize="12" fill="currentColor">{topic.name.slice(0, 14)}</text>
                  </g>
                );
              })}
            </svg>
            <p className="px-2 pb-2 text-xs text-muted-foreground">{topics.length} 个主题 · {edges.length} 条前置关系</p>
          </div>
          <aside className="space-y-4 rounded-xl border p-4" aria-label="主题详情">
            <h2 className="text-lg font-semibold">主题详情</h2>
            {selectedTopic ? (
              <>
                <p className="font-medium">{selectedTopic.name}</p>
                <p className="text-sm text-muted-foreground">模块：{selectedTopic.springModule}</p>
                <h3 className="text-sm font-semibold">前置主题</h3>
                {selectedEdges.length ? (
                  <ul className="list-inside list-disc space-y-1 text-sm">
                    {selectedEdges.map((edge) => <li key={edge.sourceId}>{topics.find((topic) => topic.id === edge.sourceId)?.name}</li>)}
                  </ul>
                ) : <p className="text-sm text-muted-foreground">该主题没有已知前置主题。</p>}
              </>
            ) : <p className="text-sm text-muted-foreground">选择一个主题查看详情。</p>}
          </aside>
        </div>
      )}
      {!loading && !error && !!topics.length && (
        <div className="space-y-2">
          <h2 className="text-lg font-semibold">主题列表（可键盘选择）</h2>
          <ul className="grid gap-2 sm:grid-cols-2">
            {topics.map((topic) => (
              <li key={topic.id}>
                <Button variant={topic.id === selectedId ? "default" : "outline"} className="w-full justify-start whitespace-normal text-left"
                  aria-pressed={topic.id === selectedId} onClick={() => setSelectedId(topic.id)}>
                  {topic.name} · {topic.springModule}
                </Button>
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}
