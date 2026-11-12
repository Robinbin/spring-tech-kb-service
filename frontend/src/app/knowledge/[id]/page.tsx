import { KnowledgeDetailView } from "@/components/knowledge-detail";
export const metadata = { title: "知识详情" };
export default async function KnowledgePage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <KnowledgeDetailView id={id} />;
}
