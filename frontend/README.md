# Spring Tech KB frontend

Next.js 15 App Router, React 19, TypeScript, Tailwind 4, shadcn/ui and AI SDK 5.

## Local development

Run the Spring Boot backend on port 8080, then from this directory:

```powershell
npm ci
Copy-Item .env.example .env.local
npm run dev
```

Open `http://localhost:3000`. Set the server-only `BACKEND_URL` in `.env.local` if the backend runs elsewhere. No browser CORS configuration is needed.

## Routes and sessions

- `/login`, `/register`: local account authentication.
- `/search`: keyword search with module/version filters.
- `/knowledge/[id]`: approved knowledge chunk, version bounds, author, source and license links.
- `/chat`: independent RAG questions with streaming text, citations, cancellation and retrieval-only status.
- `/plans`: select knowledge topics, generate a personalized prerequisite-safe plan and view history.
- `/plans/[id]`: task board, prerequisites, cumulative time, progress and completion-rate adaptation.
- `/assessment`: mixed diagnostic (reviewed choice/coding/open-ended) with frozen session snapshots.
- `/practice`: CodeMirror 6 coding answer editor with run/submit feedback panels (compile/runtime/test/static hints).
- `/report`: ability report and deterministic SVG radar visualization for an assessment session.
- `/examples`: conceptual/practical/source examples, technical comparison and project ZIP downloads.
- `/graph`: interactive, keyboard-accessible prerequisite graph and topic list, loaded through read-only topic/prerequisite APIs.
- `/achievements`: personal achievement and streak summaries plus weekly/monthly leaderboard using display names only.
- `/operations`: admin-only operational metrics for costs, prompt-cache hit ratio, batch share and circuit-breaker state.

JWTs stay in a SameSite=Lax, HttpOnly cookie; production cookies additionally require HTTPS (`Secure`). The browser never receives a token in JSON or localStorage. The BFF forwards bearer credentials only to the configured backend. Mutations check Origin. Authenticated pages check the session on entry and every 60 seconds; any API 401 clears the cookie and redirects to login. Tokens are not refreshed: expiry requires login, matching the current backend contract. Configure a persistent backend `JWT_SECRET` to preserve sessions across backend restarts.

`/api/chat` adapts Spring's token/citation/done/error SSE events into the AI SDK UI message stream; it does not call an LLM directly or expose API keys. Requests remain single-turn, even though prior answers remain visible locally.

For production, serve the frontend and BFF from the same public HTTPS origin. Preserve the public Host/Origin through the reverse proxy; disable buffering for streaming responses and allow at least 150 seconds for chat requests.

Phase 2/3 learner routes use the same origin-checked, allowlisted BFF; admin question/topic authoring is deliberately not proxied. Generation and coding checks may take up to 120 seconds. Source/license attribution remains visible, and revoked evidence is rejected by the backend. Generated code is displayed as text, never executed or silently indexed. Project archives pin Java 21 / Spring Boot 4.1.2 and optionally include an owned example as documentation only.

Phase 4 uses the backend contracts directly: `GET /api/achievements` returns `{ achievements: [{ code, title, description, awardedAt }], currentStreakDays, completedTasks, completedAssessments, masteredTopics, leaderboardOptedIn, displayAlias }`; `GET /api/achievements/leaderboard?days=7|30&limit=20` returns an array of `{ displayAlias, score, activeDays, completedTasks, completedAssessments }`. The UI maps these to the weekly/monthly selector and displays aliases only.

The admin operations panel uses `GET /api/operations/summary?days=7|14|30`, whose response contains `windowDays`, `byModelTier` (`knownCostUsd`, `unknownCostCalls`, token/caching counts and ratios, batch counts and ratios), and `circuitBreakers` (`name`, `state`). Cost and ratios are aggregated from the backend's tier rows; null ratios display as unavailable. The operations endpoint must enforce authenticated JWT `ADMIN` authority; the UI role check is not a security boundary. The crawler panel uses `GET /api/knowledge/crawl/catalog` (`sourceKey`, `title`, `sourceUri`, `springModule`, `version`, `licenseType`, `licenseUrl`) and `POST /api/knowledge/crawl` with `{ sourceKeys, maxSources, approvalReference }`. Both crawler APIs are admin-only in the backend; the BFF allows only these exact paths/methods, never internal/admin wildcards. The graph continues to use read-only topic/prerequisite endpoints, capped at 100 topics and 300 edges, with no graph mutations.

An empty production knowledge graph or reviewed question bank is not filled with demo data. An administrator must first configure topics/prerequisites, approved source chunks and reviewed multiple-choice questions through the backend APIs documented in `docs\dev-baseline-wbs.md`. No eligible diagnostic questions produces an explicit error, not self-rated mastery or invented questions. Completed task history is immutable, and task prerequisites are enforced on the server as well as in the UI.

## Verification

```powershell
npm run lint
npm run typecheck
npm run build
npx playwright install chromium
npm run test:e2e
```

The browser suite starts a deterministic mock backend on port 18080 and a production Next.js server on port 3000. Its twenty-two cases exercise the real BFF and SSE adapter, authentication/session lifecycle, search/detail attribution, partial tokens/errors/fallback/cancellation, origin/input checks, theme/mobile width, three example levels, comparison and ZIP download, prerequisite-gated task progress/adaptation, mixed diagnostics (MCQ/coding/open-ended), CodeMirror practice feedback, report radar rendering, and Phase 4 graph, achievements/leaderboard, operations metrics, and the admin catalog crawl flow. It is not a real LLM quality test or a cross-browser certification. Keep these ports available.

The backend `EpicHKnowledgeDetailIT` separately verifies authenticated detail responses, real PostgreSQL field mapping, version/license attribution, and missing/unapproved-source rejection using Podman and port 5433. Run from the repository root with the existing Podman connection:

```powershell
$env:DOCKER_HOST = 'npipe:////./pipe/podman-machine-default'
$env:TESTCONTAINERS_RYUK_DISABLED = 'true'
mvn -q -DskipTests test-compile
mvn -q '-Dit.test=EpicHKnowledgeDetailIT' failsafe:integration-test failsafe:verify
```

For Phase 2, replace the integration selector with
`'-Dit.test=Phase2KnowledgeIT,Phase2LearningIT,GenerationIT'`. These tests run
sequentially against the same fixed host port; do not launch separate Maven
integration runs concurrently. They cover graph closure/cycle rejection, real
authenticated MCP initialization/discovery/calls, prompt/cost provenance,
owned plans, prerequisite-gated progress/adaptation, reviewed diagnostics and
generation persistence/downloads. Model responses remain controlled fixtures;
they do not certify live Anthropic schema support or output quality.

The production build currently emits a non-fatal dynamic-dependency warning from AI SDK's provider-utils. Real Anthropic/TEI traffic and proxy streaming latency still require environment-specific acceptance.
