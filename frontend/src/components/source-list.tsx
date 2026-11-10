import { safeUrl } from "@/lib/types";

export type AttributedSource = {
  id: string;
  title: string;
  url: string;
  license: string | null;
  licenseUrl?: string | null;
  author?: string | null;
};

export function SourceList({ sources }: { sources: AttributedSource[] }) {
  return <aside className="space-y-3 rounded-lg border p-4 text-sm">
    <h3 className="font-semibold">来源与许可</h3>
    {!sources.length && <p className="text-muted-foreground">未关联已审核来源，请核实知识库内容。</p>}
    {sources.map((source) => <div key={source.id} className="space-y-1">
      <p>{safeUrl(source.url) ? <a href={safeUrl(source.url)} className="underline" target="_blank" rel="noopener noreferrer">{source.title}</a> : source.title}</p>
      <p className="text-muted-foreground">许可：{source.license || "未标注"}{source.author ? ` · ${source.author}` : ""}</p>
      {safeUrl(source.licenseUrl) && <a className="underline" href={safeUrl(source.licenseUrl)} target="_blank" rel="noopener noreferrer">许可条款</a>}
    </div>)}
  </aside>;
}
