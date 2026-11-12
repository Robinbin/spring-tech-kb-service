import { TaskBoard } from "@/components/task-board";

export default async function PlanPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <TaskBoard id={id} />;
}
