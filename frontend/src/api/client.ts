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
 * Cookie 認証で CSRF 対策を省くと、外部サイトから利用者の権限で利用者の追加や設定変更が
 * 実行できてしまう（docs/spec/07-api-design.md 1.2）。
 */
api.use({
  onRequest({ request }) {
    if (SAFE_METHODS.has(request.method.toUpperCase())) return request
    const token = readCookie('XSRF-TOKEN')
    if (token) request.headers.set('X-XSRF-TOKEN', token)
    return request
  },
})

export function readCookie(name: string): string | null {
  for (const part of document.cookie.split(';')) {
    const [key, ...rest] = part.trim().split('=')
    if (key === name) return decodeURIComponent(rest.join('='))
  }
  return null
}

/** ProblemDetail（RFC 9457）から機械可読なエラーコードを取り出す。 */
export function errorCodeOf(error: unknown): string {
  if (error && typeof error === 'object' && 'errorCode' in error) {
    return String((error as { errorCode: unknown }).errorCode)
  }
  return 'UNKNOWN'
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
