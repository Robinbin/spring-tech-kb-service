"use client";

export type Topic = {
  id: string;
  name: string;
  springModule: string | null;
  description: string | null;
  prerequisites: string[];
};

export function TopicPicker({ topics, selected, onChange }: {
  topics: Topic[];
  selected: string[];
  onChange: (ids: string[]) => void;
}) {
  return <fieldset className="space-y-3 rounded-xl border p-4">
    <legend className="px-1 text-sm font-semibold">选择知识主题</legend>
    {!topics.length && <p role="status" className="text-sm text-muted-foreground">尚无知识主题，请联系管理员配置知识图谱。</p>}
    <div className="grid gap-3 sm:grid-cols-2">
      {topics.map((topic) => <label key={topic.id} className="flex items-start gap-2 text-sm">
        <input type="checkbox" className="mt-1" checked={selected.includes(topic.id)}
          onChange={(event) => onChange(event.target.checked ? [...selected, topic.id] : selected.filter((id) => id !== topic.id))} />
        <span><span className="font-medium">{topic.name}</span>
          {topic.springModule && <span className="ml-2 text-muted-foreground">{topic.springModule}</span>}
          {topic.description && <span className="mt-1 block text-muted-foreground">{topic.description}</span>}
          {!!topic.prerequisites.length && <span className="block text-xs text-muted-foreground">{topic.prerequisites.length} 个直接前置主题，将自动补齐依赖。</span>}
        </span>
      </label>)}
    </div>
  </fieldset>;
}
