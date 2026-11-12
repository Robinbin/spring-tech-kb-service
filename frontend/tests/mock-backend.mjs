import http from "node:http";

const id = "11111111-1111-1111-1111-111111111111";
const source = {
  id, title: "Spring Bean Lifecycle", url: "https://docs.spring.io/beans",
  license: "APACHE_2", licenseUrl: "https://www.apache.org/licenses/LICENSE-2.0",
  author: "Spring", version: "6.1",
};
const user = { id, email: "student@example.com", username: "student", role: "ADMIN" };
const generatedId = "22222222-2222-2222-2222-222222222222";
const generations = new Map();
const prerequisiteId = "33333333-3333-3333-3333-333333333333";
const topicId = "44444444-4444-4444-4444-444444444444";
const planId = "55555555-5555-5555-5555-555555555555";
const taskId = "66666666-6666-6666-6666-666666666666";
const dependentTaskId = "77777777-7777-7777-7777-777777777777";
const reviewTaskId = "88888888-8888-8888-8888-888888888888";
const assessmentId = "99999999-9999-9999-9999-999999999999";
const codingQuestionId = "dddddddd-dddd-dddd-dddd-dddddddddddd";
const openQuestionId = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
const noBankId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
const catalogItems = [
  { sourceKey: "spring-framework", title: "Spring Framework Reference", sourceUri: "https://docs.spring.io/spring-framework/reference/", springModule: "core", version: "6.2", licenseType: "APACHE_2", licenseUrl: "https://www.apache.org/licenses/LICENSE-2.0" },
  { sourceKey: "spring-boot", title: "Spring Boot Reference", sourceUri: "https://docs.spring.io/spring-boot/reference/", springModule: "boot", version: "3.5", licenseType: "APACHE_2", licenseUrl: "https://www.apache.org/licenses/LICENSE-2.0" },
];
const topics = [
  { id: prerequisiteId, name: "容器基础", springModule: "core", description: null, prerequisites: [] },
  { id: topicId, name: "Bean 生命周期", springModule: "core", description: null, prerequisites: [prerequisiteId] },
  { id: noBankId, name: "尚无审核题目的主题", springModule: "core", description: null, prerequisites: [] },
];
let plan = null;
let assessment = null;

function json(response, status, value) {
  response.writeHead(status, { "Content-Type": "application/json" });
  response.end(JSON.stringify(value));
}

http.createServer(async (request, response) => {
  const url = new URL(request.url, "http://localhost:18080");
  if (url.pathname === "/health") return json(response, 200, { ok: true });
  let text = "";
  for await (const chunk of request) text += chunk;
  const body = text ? JSON.parse(text) : {};
  if (url.pathname === "/api/auth/login" || url.pathname === "/api/auth/register") {
    if (body.password !== "correct-horse-battery") return json(response, 401, { error: "invalid_credentials" });
    return json(response, url.pathname.endsWith("register") ? 201 : 200, {
      accessToken: "browser-test-token", tokenType: "Bearer",
      expiresAt: new Date(Date.now() + 900_000).toISOString(), user,
    });
  }
  if (request.headers.authorization !== "Bearer browser-test-token") return json(response, 401, { error: "unauthorized" });
  if (url.pathname === "/api/users/me") return json(response, 200, user);
  if (url.pathname === "/api/achievements") return json(response, 200, {
    achievements: [
      { code: "first-step", title: "初次探索", description: "完成第一项学习活动。", awardedAt: "2026-10-28T08:00:00.000Z" },
      { code: "steady-learner", title: "稳定前行", description: "连续学习达到 7 天。", awardedAt: "2026-10-29T08:00:00.000Z" },
    ],
    currentStreakDays: 7, completedTasks: 9, completedAssessments: 2, masteredTopics: 4,
    leaderboardOptedIn: true, displayAlias: "Learner-ABCD1234",
  });
  if (url.pathname === "/api/achievements/leaderboard") {
    const days = Number(url.searchParams.get("days"));
    const multiplier = days === 7 ? 1 : 4;
    return json(response, 200, [
      { displayAlias: "春日学习者", score: 1280 * multiplier, activeDays: 7, completedTasks: 8, completedAssessments: 1 },
      { displayAlias: "代码旅人", score: 1120 * multiplier, activeDays: 6, completedTasks: 7, completedAssessments: 1 },
      { displayAlias: "test.person@example.com", score: 980 * multiplier, activeDays: 5, completedTasks: 6, completedAssessments: 1 },
    ]);
  }
  if (url.pathname === "/api/knowledge/crawl/catalog" && request.method === "GET") return json(response, 200, catalogItems);
  if (url.pathname === "/api/knowledge/crawl" && request.method === "POST") {
    if (!Array.isArray(body.sourceKeys) || body.sourceKeys.length < 1 || body.sourceKeys.length > 3
      || body.sourceKeys.some((key) => !catalogItems.some((item) => item.sourceKey === key))
      || body.maxSources < body.sourceKeys.length || body.maxSources > 3
      || typeof body.approvalReference !== "string" || !body.approvalReference.trim()) {
      return json(response, 400, {});
    }
    return json(response, 200, {
      processedSources: body.sourceKeys.length,
      results: body.sourceKeys.map((sourceKey) => ({
        sourceKey, status: "INGESTED", sourceId: id, chunkCount: 12, contentHash: "fixture-hash",
      })),
    });
  }
  if (url.pathname === "/api/knowledge/topics") return json(response, 200, topics);
  if (url.pathname.startsWith("/api/knowledge/topics/") && url.pathname.endsWith("/prerequisites")) {
    const topic = topics.find((item) => url.pathname === `/api/knowledge/topics/${item.id}/prerequisites`);
    if (!topic) return json(response, 404, {});
    return json(response, 200, topic.prerequisites.map((prerequisite) => topics.find((item) => item.id === prerequisite)).filter(Boolean));
  }
  if (url.pathname === "/api/operations/summary") {
    const days = Number(url.searchParams.get("days"));
    if (![7, 14, 30].includes(days)) return json(response, 400, {});
    return json(response, 200, {
      windowStart: "2026-10-23T00:00:00.000Z",
      windowEnd: "2026-10-30T00:00:00.000Z",
      windowDays: days,
      byModelTier: [{
        modelTier: "STANDARD", calls: 100, inputTokens: 28, outputTokens: 500,
        cacheReadTokens: 72, cacheReadRatio: 0.72, batchCalls: 38, batchShare: 0.38,
        knownCostUsd: days === 7 ? 12.3456 : days === 14 ? 24.5678 : 45.6789,
        unknownCostCalls: 2, errorCalls: 1,
      }],
      circuitBreakers: [
        { name: "llm-lite", state: "CLOSED" },
        { name: "llm-standard", state: "HALF_OPEN" },
      ],
    });
  }
  if (url.pathname === "/api/learning/plans" && request.method === "GET") return json(response, 200, plan ? [{
    ...plan, taskCount: plan.tasks.length, completedTaskCount: plan.tasks.filter((task) => task.status === "COMPLETED").length,
  }] : []);
  if (url.pathname === "/api/learning/plans" && request.method === "POST") {
    plan = {
      id: planId, title: "Spring 容器学习计划", goal: body.goal, status: "ACTIVE",
      modelId: "test-model", promptVersion: "learning-plan:v1", sourceTopicIds: [prerequisiteId, topicId],
      sources: [source], tasks: [
        { id: taskId, topicId: prerequisiteId, title: "学习容器基础", description: "理解容器的作用。",
          order: 0, status: "PENDING", prerequisiteTaskIds: [], targetVersion: "6.1", timeSpentSeconds: 0 },
        { id: dependentTaskId, topicId, title: "学习 Bean 生命周期", description: "跟踪初始化与销毁。",
          order: 1, status: "PENDING", prerequisiteTaskIds: [taskId], targetVersion: "6.1", timeSpentSeconds: 0 },
      ],
    };
    return json(response, 201, plan);
  }
  if (url.pathname === `/api/learning/plans/${planId}`) return json(response, plan ? 200 : 404, plan ?? {});
  if (url.pathname === `/api/learning/plans/${planId}/adapt` && plan) {
    if (plan.tasks.filter((task) => task.status === "COMPLETED").length / plan.tasks.length < 0.5
      && !plan.tasks.some((task) => task.id === reviewTaskId)) {
      plan.tasks.push({ id: reviewTaskId, topicId: prerequisiteId, title: "针对性复习容器基础", description: "根据完成率增加的复习任务。",
        order: 2, status: "PENDING", prerequisiteTaskIds: [], targetVersion: "6.1", timeSpentSeconds: 0 });
    }
    return json(response, 200, plan);
  }
  if (url.pathname.startsWith("/api/learning/tasks/") && request.method === "PATCH" && plan) {
    const task = plan.tasks.find((candidate) => url.pathname.endsWith(candidate.id));
    if (!task) return json(response, 404, {});
    if (["IN_PROGRESS", "COMPLETED"].includes(body.status)
      && task.prerequisiteTaskIds.some((parent) => plan.tasks.find((candidate) => candidate.id === parent)?.status !== "COMPLETED")) {
      return json(response, 409, {});
    }
    task.status = body.status; task.timeSpentSeconds = body.timeSpentSeconds;
    if (plan.tasks.every((candidate) => candidate.status === "COMPLETED")) plan.status = "COMPLETED";
    return json(response, 200, task);
  }
  if (url.pathname === "/api/assessments/initial") {
    if (body.topicIds.includes(noBankId)) return json(response, 409, { error: "No reviewed questions" });
    assessment = {
      id: assessmentId, sessionType: "INITIAL_DIAGNOSTIC", status: "IN_PROGRESS",
      totalQuestions: 3, correctCount: null, score: null,
      questions: [
        {
          id: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
          topicId,
          type: "MCQ",
          content: "Bean 由哪个容器管理？",
          options: [{ label: "A", text: "Spring 容器" }, { label: "B", text: "浏览器" }],
          language: null,
          starterCode: null,
          userAnswer: null,
          isCorrect: null,
          correctAnswer: null,
          explanation: null,
          source,
        },
        {
          id: codingQuestionId,
          topicId,
          type: "CODING",
          content: "补全 BeanFactoryPostProcessor 示例中的 TODO。",
          options: [],
          language: "java",
          starterCode: "public class BeanDemo {\n  public static void main(String[] args) {\n    // TODO\n  }\n}\n",
          userAnswer: null,
          isCorrect: null,
          correctAnswer: null,
          explanation: null,
          source,
        },
        {
          id: openQuestionId,
          topicId,
          type: "OPEN_ENDED",
          content: "简述 Bean 初始化回调与销毁回调的关键差异。",
          options: [],
          language: null,
          starterCode: null,
          userAnswer: null,
          isCorrect: null,
          correctAnswer: null,
          explanation: null,
          source,
        },
      ],
    };
    return json(response, 201, assessment);
  }
  if (url.pathname === `/api/assessments/${assessmentId}`) return json(response, assessment ? 200 : 404, assessment ?? {});
  if (url.pathname === `/api/assessments/${assessmentId}/answers` && assessment) {
    const question = assessment.questions.find((item) => item.id === body.questionId);
    if (!question) return json(response, 404, {});
    question.userAnswer = body.answer; question.isCorrect = body.answer === "A";
    if (question.type === "OPEN_ENDED") question.isCorrect = null;
    const complete = assessment.questions.every((item) => item.userAnswer !== null);
    if (complete) {
      assessment.status = "COMPLETED";
      assessment.correctCount = assessment.questions.filter((item) => item.isCorrect).length;
      assessment.score = Math.round(assessment.correctCount / assessment.totalQuestions * 100);
      assessment.questions.forEach((item) => {
        item.correctAnswer = item.type === "MCQ" ? "A" : null;
        item.explanation = item.type === "OPEN_ENDED" ? "主观题已记录，待后续 Rubric 评估。" : "已审核解析。";
      });
    }
    return json(response, 200, { questionId: question.id, answer: body.answer, isCorrect: question.isCorrect, completed: complete, score: assessment.score });
  }
  if (url.pathname === `/api/assessments/${assessmentId}/coding/run` && request.method === "POST" && assessment) {
    const code = String(body.code ?? "");
    return json(response, 200, {
      status: code.includes("TODO") ? "FAILED" : "SUCCESS",
      compile: code.includes("class") ? [] : ["未检测到 class 定义。"],
      runtime: code.includes("System.out.println") ? ["标准输出已捕获。"] : ["建议输出关键生命周期日志。"],
      tests: code.includes("TODO") ? ["测试 testBeanLifecycle 未通过：仍存在 TODO。"] : ["测试 testBeanLifecycle 通过。"],
      staticHints: ["避免在回调中执行阻塞 I/O。", "请补充异常处理。"],
      output: code.includes("println") ? "Bean 初始化完成" : "",
    });
  }
  if (url.pathname === `/api/assessments/${assessmentId}/coding/submit` && request.method === "POST" && assessment) {
    const question = assessment.questions.find((item) => item.id === body.questionId && item.id === codingQuestionId);
    if (!question) return json(response, 404, {});
    question.userAnswer = String(body.code ?? "");
    question.isCorrect = !question.userAnswer.includes("TODO");
    const complete = assessment.questions.every((item) => item.userAnswer !== null);
    if (complete) {
      assessment.status = "COMPLETED";
      assessment.correctCount = assessment.questions.filter((item) => item.isCorrect).length;
      assessment.score = Math.round(assessment.correctCount / assessment.totalQuestions * 100);
      assessment.questions.forEach((item) => {
        item.correctAnswer = item.type === "MCQ" ? "A" : null;
        item.explanation = item.type === "OPEN_ENDED" ? "主观题已记录，待后续 Rubric 评估。" : "已审核解析。";
      });
    }
    return json(response, 200, { questionId: question.id, accepted: question.isCorrect, completed: complete, score: assessment.score });
  }
  if (url.pathname === `/api/assessments/${assessmentId}/report` && request.method === "GET") {
    return json(response, 200, {
      assessmentId,
      generatedAt: "2026-10-30T00:00:00.000Z",
      overallScore: assessment?.score ?? 67,
      levels: [
        { label: "概念理解", value: 82 },
        { label: "编码实现", value: 71 },
        { label: "问题定位", value: 64 },
        { label: "工程规范", value: 69 },
        { label: "表达总结", value: 58 },
      ],
      strengths: ["概念题稳定性较好", "编程题结构完整"],
      improvements: ["主观题论证深度不足", "异常场景覆盖不充分"],
    });
  }
  if (url.pathname === "/api/generation/examples" || url.pathname === "/api/generation/comparisons") {
    if (body.query === "no-evidence") return json(response, 422, { error: "No approved evidence" });
    const example = url.pathname.endsWith("examples");
    const result = {
      id: generatedId, kind: example ? "EXAMPLE" : "COMPARISON",
      title: example ? "Bean 回调示例" : "容器技术对比", content: "基于审核来源的说明。",
      code: example ? "public class BeanExample {}" : "", language: "java",
      aiGenerated: true, origin: "AI_GENERATED", sources: [source],
      modelId: "test-model", promptVersion: example ? "code-example:v1" : "technical-comparison:v1",
      level: example ? body.level : null, springVersion: body.springVersion, project: null,
    };
    generations.set(generatedId, result);
    return json(response, 201, result);
  }
  if (url.pathname === "/api/generation/projects") {
    response.writeHead(201, {
      "Content-Type": "application/zip", "Content-Disposition": `attachment; filename="${body.artifactId}.zip"`,
      "X-Generation-Id": generatedId,
    });
    return response.end(Buffer.from("504b0506000000000000000000000000000000000000", "hex"));
  }
  if (url.pathname === `/api/generation/${generatedId}`) {
    return json(response, generations.has(generatedId) ? 200 : 404, generations.get(generatedId) ?? {});
  }
  if (url.pathname === "/api/search") return json(response, 200, {
    results: url.searchParams.get("q") === "empty" ? [] : [{
      id, content: "Bean 初始化与销毁回调。", springVersion: "6.1 - 6.2", source,
    }],
  });
  if (url.pathname === `/api/knowledge/${id}`) return json(response, 200, {
    id, content: "Bean 初始化与销毁回调。", springModule: "core",
    springVersionMin: "6.1", springVersionMax: "6.2", source,
  });
  if (url.pathname === "/api/chat/rag") {
    response.writeHead(200, { "Content-Type": "text/event-stream" });
    function event(name, value) {
      response.write(`event:${name}\r\ndata:${JSON.stringify({ type: name, ...value })}\r\n\r\n`);
    }
    event("citation", { citation: {
      chunkId: id, title: source.title, url: source.url, springVersion: "6.1",
      springModule: "core", license: source.license,
    } });
    // Split a multibyte character across network chunks to exercise the SSE decoder.
    const first = Buffer.from(`event:token\ndata:${JSON.stringify({ type: "token", text: "第一段" })}\n\n`);
    const split = first.indexOf(Buffer.from("第")) + 1;
    response.write(first.subarray(0, split));
    setTimeout(() => response.write(first.subarray(split)), 30);
    setTimeout(() => {
      if (body.query === "stream-error") {
        event("error", { error: "Provider unavailable" });
      } else {
        event("token", { text: "，第二段。" });
        event("done", { route: body.query === "fallback" ? "RETRIEVAL_ONLY" : "FAST_PATH", cacheHit: false });
      }
      response.end();
    }, 1200);
    return;
  }
  return json(response, 404, { error: "not_found" });
}).listen(18080, "127.0.0.1");
