import { describe, it, expect, vi, beforeEach } from 'vitest'

/** ブラウザと同じく、相対 URL を画面のオリジンで解決する（Node の Request は相対 URL を受け付けない）。 */
class BrowserRequest extends Request {
  constructor(input: RequestInfo | URL, init?: RequestInit) {
    super(typeof input === 'string' ? new URL(input, window.location.origin) : input, init)
  }
}

/** fetch と Request を差し替えてから読み込む（openapi-fetch は作成時のものを使う）。 */
async function loadClient(status: number) {
  vi.resetModules()
  vi.stubGlobal('Request', BrowserRequest)
  vi.stubGlobal(
    'fetch',
    vi.fn(
      async () => new Response('{}', { status, headers: { 'Content-Type': 'application/json' } }),
    ),
  )
  return import('./client')
}

describe('API の 401', () => {
  beforeEach(() => {
    vi.unstubAllGlobals()
  })

  it('判定の取得が 401 なら、登録した処理を呼ぶ', async () => {
    const { api, onUnauthorized } = await loadClient(401)
    const handler = vi.fn()
    onUnauthorized(handler)

    await api.GET('/api/v1/release', { params: { query: {} } })

    expect(handler).toHaveBeenCalledOnce()
  })

  it('ログイン状態の確認（/api/v1/me）の 401 では呼ばない', async () => {
    const { api, onUnauthorized } = await loadClient(401)
    const handler = vi.fn()
    onUnauthorized(handler)

    await api.GET('/api/v1/me')

    expect(handler).not.toHaveBeenCalled()
  })

  it('成功した応答では呼ばない', async () => {
    const { api, onUnauthorized } = await loadClient(200)
    const handler = vi.fn()
    onUnauthorized(handler)

    await api.GET('/api/v1/release', { params: { query: {} } })

    expect(handler).not.toHaveBeenCalled()
  })
})
