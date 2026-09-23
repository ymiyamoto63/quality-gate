<script setup lang="ts">
import { computed, ref } from 'vue'
import { useWaiverStore, type CreateWaiverRequest } from '@/stores/waivers'

/**
 * 免除の登録フォーム（docs/08-screen-design.md 4.7）。
 *
 * 理由は 20 文字以上、期限は既定 30 日・最大 90 日。「この違反は判定から除外されます」
 * と明示してから実行させる。
 */
const props = defineProps<{
  repositoryId: string
  scope: 'FINDING' | 'METRIC'
  metricId: string
  fingerprint?: string | null
  title: string
}>()
const emit = defineEmits<{ done: []; cancel: [] }>()

const store = useWaiverStore()

const MIN_REASON = 20
const DAY_MS = 24 * 60 * 60 * 1000

const REASON_CATEGORIES: {
  value: CreateWaiverRequest['reasonCategory']
  label: string
  days: number
}[] = [
  { value: 'NO_FIX_AVAILABLE', label: '修正版未提供（推奨 30 日）', days: 30 },
  { value: 'PLANNED', label: '対応計画済み（推奨 30 日）', days: 30 },
  { value: 'UNREACHABLE', label: '到達不能（推奨 90 日）', days: 90 },
  { value: 'FALSE_POSITIVE', label: '誤検知（推奨 90 日）', days: 90 },
]

function dateAfter(days: number): string {
  return new Date(Date.now() + days * DAY_MS).toISOString().slice(0, 10)
}

const reasonCategory = ref<CreateWaiverRequest['reasonCategory']>('NO_FIX_AVAILABLE')
const reason = ref('')
const expiresOn = ref(dateAfter(30))
const confirmed = ref(false)
const submitting = ref(false)
const errorMessage = ref<string | null>(null)
const fieldErrors = ref<Record<string, string>>({})

const minDate = dateAfter(1)
const maxDate = dateAfter(89)
const reasonLength = computed(() => reason.value.trim().length)
const reasonTooShort = computed(() => reasonLength.value < MIN_REASON)

async function submit(): Promise<void> {
  if (reasonTooShort.value || !confirmed.value) return
  submitting.value = true
  // 期限はその日の終わり（日本時間）までとする
  const expiresAt = new Date(`${expiresOn.value}T23:59:59+09:00`).toISOString()
  const result = await store.create({
    repositoryId: props.repositoryId,
    scope: props.scope,
    metricId: props.metricId,
    fingerprint: props.fingerprint ?? null,
    reasonCategory: reasonCategory.value,
    reason: reason.value.trim(),
    expiresAt,
  })
  submitting.value = false
  if (result) {
    errorMessage.value = result.message
    fieldErrors.value = result.fields
    return
  }
  emit('done')
}
</script>

<template>
  <form class="qg-form" novalidate @submit.prevent="submit">
    <p>
      対象: <strong>{{ title }}</strong>
      <span class="qg-muted">（{{ metricId }}{{ scope === 'METRIC' ? '・指標全体' : '' }}）</span>
    </p>

    <label>
      理由区分
      <select v-model="reasonCategory">
        <option v-for="c in REASON_CATEGORIES" :key="c.value" :value="c.value">
          {{ c.label }}
        </option>
      </select>
    </label>

    <label>
      理由と根拠（必須・{{ MIN_REASON }} 文字以上）
      <textarea
        v-model="reason"
        rows="4"
        required
        :aria-invalid="reasonTooShort && reason.length > 0 ? 'true' : 'false'"
        aria-describedby="waiver-reason-count waiver-reason-error"
      />
      <span id="waiver-reason-count" class="qg-form__hint" aria-live="polite">
        {{ reasonLength }} /
        {{ MIN_REASON }} 文字以上。回避策や参照先（PR・チケット）を書いてください
      </span>
      <span v-if="fieldErrors.reason" id="waiver-reason-error" class="qg-form__error">
        {{ fieldErrors.reason }}
      </span>
    </label>

    <label>
      期限（必須・最長 90 日）
      <input
        v-model="expiresOn"
        type="date"
        required
        :min="minDate"
        :max="maxDate"
        aria-describedby="waiver-expiry-hint"
      />
      <span id="waiver-expiry-hint" class="qg-form__hint">
        期限を過ぎると自動で無効になり、次の判定から再び数えられます
      </span>
    </label>

    <label class="qg-confirm">
      <input v-model="confirmed" type="checkbox" />
      <span>
        {{
          scope === 'METRIC'
            ? 'この指標は期限まで参考値となり、合否に影響しなくなります'
            : 'この違反は判定から除外されます'
        }}。 登録は監査ログに記録されます。
      </span>
    </label>

    <p v-if="errorMessage" class="qg-form__error" role="alert">{{ errorMessage }}</p>

    <div class="qg-form__actions">
      <button type="button" class="qg-button" @click="emit('cancel')">キャンセル</button>
      <button
        type="submit"
        class="qg-button qg-button--primary"
        :disabled="reasonTooShort || !confirmed || submitting"
      >
        免除を登録
      </button>
    </div>
  </form>
</template>

<style scoped>
.qg-confirm {
  display: flex !important;
  grid-template-columns: none;
  align-items: flex-start;
  gap: 0.5rem !important;
  font-weight: 400 !important;
}
</style>
