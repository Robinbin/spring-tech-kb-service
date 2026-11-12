export type Achievement = {
  code: string;
  title: string;
  description: string;
  awardedAt: string;
};

export type AchievementsResponse = {
  achievements: Achievement[];
  currentStreakDays: number;
  completedTasks: number;
  completedAssessments: number;
  masteredTopics: number;
  leaderboardOptedIn: boolean;
  displayAlias: string | null;
};

export type LeaderboardEntry = {
  displayAlias: string;
  score: number;
  activeDays: number;
  completedTasks: number;
  completedAssessments: number;
};

export type CatalogItem = {
  sourceKey: string;
  title: string;
  sourceUri: string;
  springModule: string;
  version: string;
  licenseType: string;
  licenseUrl: string;
};

export type CrawlRunResult = {
  processedSources: number;
  results: {
    sourceKey: string;
    status: string;
    sourceId: string | null;
    chunkCount: number;
    contentHash: string | null;
  }[];
};

export type OperationsSummary = {
  windowStart: string;
  windowEnd: string;
  windowDays: number;
  byModelTier: {
    modelTier: string;
    calls: number;
    inputTokens: number;
    outputTokens: number;
    cacheReadTokens: number;
    cacheReadRatio: number | null;
    batchCalls: number;
    batchShare: number | null;
    knownCostUsd: number | null;
    unknownCostCalls: number;
    errorCalls: number;
  }[];
  circuitBreakers: { name: string; state: string }[];
};
