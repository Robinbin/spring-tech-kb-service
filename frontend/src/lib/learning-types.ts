import type { AttributedSource } from "@/components/source-list";

export type TaskStatus = "PENDING" | "IN_PROGRESS" | "COMPLETED" | "SKIPPED";
export type LearningTask = {
  id: string;
  topicId: string | null;
  title: string;
  description: string | null;
  order: number;
  status: TaskStatus;
  prerequisiteTaskIds: string[];
  targetVersion: string | null;
  timeSpentSeconds: number;
};
export type PlanSummary = {
  id: string;
  title: string;
  goal: string | null;
  status: string;
  modelId: string | null;
  promptVersion: string | null;
  sourceTopicIds: string[];
  taskCount: number;
  completedTaskCount: number;
  sources: AttributedSource[];
};
export type LearningPlan = Omit<PlanSummary, "taskCount" | "completedTaskCount"> & { tasks: LearningTask[] };
export type DiagnosticQuestion = {
  id: string;
  topicId: string;
  type: "MCQ" | "CODING" | "OPEN_ENDED";
  content: string;
  options: { label: string; text: string }[];
  language: string | null;
  starterCode: string | null;
  userAnswer: string | null;
  isCorrect: boolean | null;
  correctAnswer: string | null;
  explanation: string | null;
  source: AttributedSource;
};
export type Diagnostic = {
  id: string;
  sessionType: string;
  status: "IN_PROGRESS" | "COMPLETED" | "ABANDONED";
  totalQuestions: number;
  correctCount: number | null;
  score: number | null;
  questions: DiagnosticQuestion[];
};

export type PracticeRunResult = {
  status: "SUCCESS" | "FAILED";
  compile: string[];
  runtime: string[];
  tests: string[];
  staticHints: string[];
  output: string;
};

export type AbilityReport = {
  assessmentId: string;
  generatedAt: string;
  overallScore: number;
  levels: { label: string; value: number }[];
  strengths: string[];
  improvements: string[];
};
