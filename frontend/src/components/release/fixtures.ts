import type { components } from '@/api/schema'

/**
 * リリース判定の部品のテストで使う API 応答の雛形。値は合格 1 件だけの判定で、差分は overrides で渡す。
 */
type Report = components['schemas']['ReleaseReportResponse']
type Row = components['schemas']['ReleaseMetric']
type Guide = components['schemas']['ReleaseGuide']
type HistoryItem = components['schemas']['ReleaseHistoryItem']

export function metricRow(overrides: Partial<Row> = {}): Row {
  return {
    metricId: 'M-01',
    name: 'ブランチカバレッジ',
    componentName: 'backend',
    variantLabel: null,
    status: 'PASS',
    value: 85,
    unit: 'percent',
    threshold: '≥ 70%',
    thresholdRelaxed: false,
    defaultThreshold: null,
    excludedFileCount: 0,
    reason: null,
    findings: [],
    findingCount: 0,
    ...overrides,
  }
}

export function guide(overrides: Partial<Guide> = {}): Guide {
  return {
    metricId: 'M-01',
    name: 'ブランチカバレッジ',
    basis: 'INDUSTRY',
    basisLabel: '業界の目安',
    summary: '分岐のうちテストで通った割合',
    rationale: '根拠',
    risk: 'リスク',
    tools: 'JaCoCo',
    definition: '定義',
    ...overrides,
  }
}

export function releaseReport(overrides: Partial<Report> = {}): Report {
  return {
    repositoryFullName: 'example/app',
    commitSha: '1'.repeat(40),
    commitUrl: null,
    decision: 'RELEASABLE',
    decisionReason: '判定した 1 件の指標が、すべて合格ラインを満たしています。',
    run: { measuredAt: '2026-09-20T03:00:00Z', tags: [], ciRunUrl: null, baseCommitSha: null },
    counts: { judged: 1, passed: 1, failed: 0 },
    metrics: [metricRow()],
    notJudged: [],
    conditions: [],
    guides: [],
    ...overrides,
  }
}

export function historyItem(overrides: Partial<HistoryItem> = {}): HistoryItem {
  return {
    commitSha: 'a'.repeat(40),
    measuredAt: '2026-09-20T03:00:00Z',
    ref: 'v1.0.0',
    tags: ['v1.0.0'],
    verdict: 'PASS',
    judgedCount: 12,
    notJudgedCount: 0,
    thresholdRelaxed: false,
    filesExcluded: false,
    ...overrides,
  }
}
