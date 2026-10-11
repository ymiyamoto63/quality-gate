import createClient from 'openapi-fetch'
import type { paths } from './schema'

/**
 * API クライアント。型は openapi.yml から生成した schema.d.ts に由来する。
 *
 * 手書きの型を併存させない。API が変わったときに、コンパイルエラーになる箇所と
 * 黙って型が合わなくなる箇所が混在するため。
 */
export const api = createClient<paths>({
  baseUrl: '/',
  credentials: 'same-origin',
})

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS'])

/**
 * 更新系のリクエストに CSRF トークンを付ける。
 *
 * サーバは XSRF-TOKEN Cookie でトークンを渡し、X-XSRF-TOKEN ヘッダで送り返すことを求める。
 * Cookie 認証で CSRF 対策を省くと、外部サイトからログイン中の利用者になりすまして
 * 更新系の操作（ログアウトなど）を実行できてしまう（docs/architecture.md 6.1）。
 */
api.use({
  onRequest({ request }) {
    if (SAFE_METHODS.has(request.method.toUpperCase())) return request
    const token = readCookie('XSRF-TOKEN')
    if (token) request.headers.set('X-XSRF-TOKEN', token)
    return request
  },
})

/** ログイン状態を確かめる口。ここの 401 は「未ログイン」の意味で、ストアが自分で扱う。 */
const ME_PATH = '/api/v1/me'

let unauthorizedHandler: (() => void) | null = null

/**
 * API が 401 を返したとき（ログインの期限切れ・サーバの再起動）に呼ぶ処理を登録する。
 *
 * ログイン状態は画面を開いたときに 1 度だけ確かめるため、開いたままの画面ではセッションが切れても気づけない。
 * 401 を受けた時点でログイン画面へ戻し、ログインの後に元の画面へ戻れるようにする。
 * 遷移は router が持つため、ここでは呼び出し先だけを受け取る（client から router を読み込むと循環する）。
 */
export function onUnauthorized(handler: () => void): void {
  unauthorizedHandler = handler
}

api.use({
  onResponse({ request, response }) {
    if (
      response.status === 401 &&
      !new URL(request.url, window.location.origin).pathname.startsWith(ME_PATH)
    ) {
      unauthorizedHandler?.()
    }
    return response
  },
})

export function readCookie(name: string): string | null {
  for (const part of document.cookie.split(';')) {
    const [key, ...rest] = part.trim().split('=')
    if (key === name) return decodeURIComponent(rest.join('='))
  }
  return null
}

/** 画面に出すメッセージ。title / detail は人間向けであり、分岐には使わない。 */
export function messageOf(error: unknown, fallback = '処理に失敗しました'): string {
  if (error && typeof error === 'object') {
    const problem = error as { detail?: unknown; title?: unknown }
    if (typeof problem.detail === 'string') return problem.detail
    if (typeof problem.title === 'string') return problem.title
  }
  return fallback
}
