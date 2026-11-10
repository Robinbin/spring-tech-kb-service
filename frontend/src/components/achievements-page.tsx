"use client";

import { useEffect, useState } from "react";
import { apiFetch } from "@/lib/client-api";
import type { AchievementsResponse, LeaderboardEntry } from "@/lib/phase4-types";

function safeDate(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "日期未知" : date.toLocaleDateString("zh-CN");
}

function safeDisplayName(value: string) {
  if (value.includes("@") || /\+?\d[\d\s()-]{6,}\d/.test(value)) return "匿名学习者";
  return value.slice(0, 40);
}

export function AchievementsPage() {
  const [personal, setPersonal] = useState<AchievementsResponse | null>(null);
  const [entries, setEntries] = useState<LeaderboardEntry[]>([]);
  const [period, setPeriod] = useState<"weekly" | "monthly">("weekly");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    let active = true;
    setLoading(true);
    Promise.all([
      apiFetch("/api/backend/achievements").then((response) => response.json()),
      apiFetch(`/api/backend/achievements/leaderboard?days=${period === "weekly" ? 7 : 30}&limit=20`)
        .then((response) => response.json()),
    ]).then(([personalResult, leaderboardResult]) => {
      if (!active) return;
      setPersonal(personalResult);
      setEntries(Array.isArray(leaderboardResult) ? leaderboardResult.slice(0, 50) : []);
      setError("");
    }).catch((reason: unknown) => {
      if (active) setError(reason instanceof Error ? reason.message : "成就数据加载失败。");
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [period]);

  return (
    <section className="space-y-6">
      <header className="space-y-2">
        <p className="text-sm text-muted-foreground">学习进度</p>
        <h1 className="text-3xl font-bold">我的成就</h1>
        <p className="text-muted-foreground">查看连续学习、已解锁成就和社区学习榜单。</p>
      </header>
      {loading && <p role="status">正在加载成就…</p>}
      {error && <p role="alert" className="text-destructive">{error}</p>}
      {!loading && !error && (
        <>
          <section aria-labelledby="streak-title" className="grid gap-3 sm:grid-cols-3">
            <h2 id="streak-title" className="sr-only">连续学习概况</h2>
            <div className="rounded-xl border p-4"><p className="text-sm text-muted-foreground">当前连续学习</p><p className="mt-1 text-2xl font-bold">{personal?.currentStreakDays ?? 0} 天</p></div>
            <div className="rounded-xl border p-4"><p className="text-sm text-muted-foreground">已完成学习任务</p><p className="mt-1 text-2xl font-bold">{personal?.completedTasks ?? 0}</p></div>
            <div className="rounded-xl border p-4"><p className="text-sm text-muted-foreground">已掌握主题</p><p className="mt-1 text-2xl font-bold">{personal?.masteredTopics ?? 0}</p></div>
          </section>
          <section className="space-y-3" aria-labelledby="achievements-title">
            <h2 id="achievements-title" className="text-xl font-semibold">已获得成就</h2>
            {personal?.achievements.length ? (
              <ul className="grid gap-3 sm:grid-cols-2">
                {personal.achievements.slice(0, 100).map((achievement) => (
                  <li key={achievement.code} className="rounded-xl border p-4">
                    <h3 className="font-semibold">{achievement.title}</h3>
                    <p className="mt-1 text-sm text-muted-foreground">{achievement.description}</p>
                    <p className="mt-2 text-xs text-muted-foreground">获得于 {safeDate(achievement.awardedAt)}</p>
                  </li>
                ))}
              </ul>
            ) : <p className="rounded-lg border p-4 text-muted-foreground">还没有解锁成就，继续学习来获得第一枚成就吧。</p>}
          </section>
          <section className="space-y-3" aria-labelledby="leaderboard-title">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <h2 id="leaderboard-title" className="text-xl font-semibold">学习排行榜</h2>
              <label className="flex items-center gap-2 text-sm">
                榜单周期
                <select aria-label="榜单周期" className="rounded-md border bg-background px-3 py-2" value={period}
                  onChange={(event) => setPeriod(event.target.value as "weekly" | "monthly")}>
                  <option value="weekly">本周</option>
                  <option value="monthly">本月</option>
                </select>
              </label>
            </div>
            {entries.length ? (
              <ol className="divide-y rounded-xl border">
                {entries.map((entry, index) => (
                  <li key={index} className="flex items-center gap-4 px-4 py-3">
                    <span className="w-10 shrink-0 font-semibold" aria-label={`第 ${index + 1} 名`}>#{index + 1}</span>
                    <span className="min-w-0 flex-1 truncate">{safeDisplayName(entry.displayAlias)}</span>
                    <span className="shrink-0 text-sm text-muted-foreground">{entry.score} 学习分</span>
                  </li>
                ))}
              </ol>
            ) : <p className="rounded-lg border p-4 text-muted-foreground">该周期暂无排行榜数据。</p>}
            <p className="text-xs text-muted-foreground">榜单仅展示公开昵称和学习分，不展示邮箱或其他个人信息。</p>
          </section>
        </>
      )}
    </section>
  );
}
