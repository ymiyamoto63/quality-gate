import { test, expect, type Page } from '@playwright/test'
import { COLOR_SCHEMES, expectNoBlockingViolations } from './axe'
import runDetail from './fixtures/run-detail.json' with { type: 'json' }
import findings from './fixtures/findings.json' with { type: 'json' }
import trend from './fixtures/trend.json' with { type: 'json' }
import repositoryDetail from './fixtures/repository-detail.json' with { type: 'json' }
import runs from './fixtures/runs.json' with { type: 'json' }
import configInvalid from './fixtures/config-invalid.json' with { type: 'json' }
import repositories from './fixtures/repositories.json' with { type: 'json' }
import users from './fixtures/users.json' with { type: 'json' }
import report from './fixtures/report.json' with { type: 'json' }

/**
 * ログインが要る画面のアクセシビリティ検査（M-10）。
 *
 * 応答例は結合テスト（RunQueryApiIT など）が実物の API から書き出したものを使う。
 * 手で書いた例だと、API が変わっても検査は通り続け、実際の画面だけが壊れる。
 */
const RUN_ID = runDetail.runId
const REPOSITORY_ID = runDetail.repository.repositoryId

type Role = 'VIEWER' | 'ADMIN'

/** API のパスごとに応答例を返す。一致しない API は 404 にし、検査中の取りこぼしを目立たせる。 */
const RESPONSES: [RegExp, unknown][] = [
  [/^\/api\/v1\/runs\/[^/]+\/findings$/, findings],
  [/^\/api\/v1\/runs\/[^/]+\/artifacts$/, { items: [] }],
  [/^\/api\/v1\/runs\/[^/]+$/, runDetail],
  [/^\/api\/v1\/runs$/, runs],
  [/^\/api\/v1\/repositories\/[^/]+\/trends$/, trend],
  [/^\/api\/v1\/repositories\/[^/]+\/config$/, configInvalid],
  [/^\/api\/v1\/repositories\/[^/]+$/, repositoryDetail],
  [/^\/api\/v1\/repositories$/, repositories],
  [/^\/api\/v1\/users$/, users],
  [/^\/api\/v1\/reports$/, report],
]

async function stubApi(page: Page, role: Role): Promise<void> {
  await page.route('**/api/v1/**', (route) => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/v1/me') {
      return route.fulfill({
        json: {
          userId: '00000000-0000-0000-0000-000000000001',
          githubLogin: 'ymiyamoto63',
          displayName: '検査用',
          avatarUrl: null,
          role,
        },
      })
    }
    const match = RESPONSES.find(([pattern]) => pattern.test(path))
    return match ? route.fulfill({ json: match[1] }) : route.fulfill({ status: 404, json: {} })
  })
}

interface Target {
  path: string
  name: string
  expected: string
  role?: Role
  /** 検査の前に画面を動的な状態にする（ダイアログを開くなど。docs/08 8 章） */
  prepare?: (page: Page) => Promise<void>
}

const PAGES: Target[] = [
  { path: `/runs/${RUN_ID}`, name: 'Run 詳細', expected: '重大・高 脆弱性件数' },
  { path: `/runs/${RUN_ID}/findings`, name: '違反一覧', expected: 'critical-lib' },
  {
    path: `/repositories/${REPOSITORY_ID}/trends`,
    name: 'トレンド',
    expected: 'ブランチカバレッジ の推移',
  },
  { path: `/repositories/${REPOSITORY_ID}`, name: 'リポジトリ詳細', expected: '直近の Run' },
  // 検証エラーを該当行の下に出した状態で検査する
  {
    path: `/repositories/${REPOSITORY_ID}/config`,
    name: '設定（検証エラー）',
    expected: "'mutation_score' の誤り",
  },
  { path: '/admin/users', name: '利用者管理', expected: 'admin-user', role: 'ADMIN' },
  { path: '/admin/repositories', name: 'リポジトリ管理', expected: 'acme/web-app', role: 'ADMIN' },
  { path: '/reports', name: '品質レポート', expected: '最新の指標と期間内の変化' },
]

for (const target of PAGES) {
  for (const scheme of COLOR_SCHEMES) {
    test(`${target.name}（${scheme}）に重大なアクセシビリティ違反がない`, async ({ page }) => {
      await page.emulateMedia({ colorScheme: scheme })
      await stubApi(page, target.role ?? 'VIEWER')
      await page.goto(target.path)

      // 検査前に中身が描かれていることを確かめる。空のページは必ず「違反 0 件」になる
      await expect(page.getByText(target.expected).first()).toBeVisible()

      // 折りたたまれた中身も検査する。合格だけのカテゴリは初期状態で閉じるため、
      // 開かないと「対象外」などの行が一度も検査されない
      const collapsed = page.locator('[aria-expanded="false"]')
      while ((await collapsed.count()) > 0) {
        await collapsed.first().click()
      }

      await target.prepare?.(page)
      await expectNoBlockingViolations(page)
    })
  }
}
