import type { UIMessage } from "ai";

export type Citation = {
  chunkId: string;
  title: string;
  url: string;
  springVersion: string | null;
  springModule: string | null;
  license: string | null;
};

export type RagMessage = UIMessage<unknown, {
  citation: Citation;
  route: { route: string; cacheHit: boolean };
}>;

export type SearchResult = {
  id: string;
  content: string;
  springVersion: string | null;
  source: { id: string; url: string; title: string; license: string | null; version: string | null };
};

export type KnowledgeDetail = {
  id: string;
  content: string;
  springModule: string | null;
  springVersionMin: string | null;
  springVersionMax: string | null;
  source: {
    id: string;
    title: string;
    url: string;
    license: string | null;
    licenseUrl: string | null;
    author: string | null;
  };
};

export function safeUrl(value: string | null | undefined) {
  if (!value) return undefined;
  try {
    const url = new URL(value);
    return url.protocol === "https:" || url.protocol === "http:" ? url.href : undefined;
  } catch {
    return undefined;
  }
}
