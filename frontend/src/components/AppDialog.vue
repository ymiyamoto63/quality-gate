<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'

/**
 * モーダルダイアログ（docs/spec/08-screen-design.md A-8）。
 *
 * ネイティブの <dialog> を showModal() で開く。背景は inert になり、フォーカスは
 * ダイアログ内に閉じる。閉じたら開いた要素へフォーカスを戻す。
 *
 * dismissible=false のときは Esc と背景クリックで閉じない。一度しか表示されない値
 * （Ingest Token）を誤って閉じさせないため（4.8）。
 */
const props = withDefaults(defineProps<{ open: boolean; title: string; dismissible?: boolean }>(), {
  dismissible: true,
})
const emit = defineEmits<{ close: [] }>()

const dialog = ref<HTMLDialogElement | null>(null)
let opener: HTMLElement | null = null

watch(
  () => props.open,
  async (open) => {
    await nextTick()
    const element = dialog.value
    if (!element) return
    if (open && !element.open) {
      opener = document.activeElement instanceof HTMLElement ? document.activeElement : null
      element.showModal()
    } else if (!open && element.open) {
      element.close()
      opener?.focus()
    }
  },
  { immediate: true },
)

function onCancel(event: Event): void {
  // Esc キー。閉じてよいかは呼び出し側の状態で決める
  event.preventDefault()
  if (props.dismissible) emit('close')
}

function onClick(event: MouseEvent): void {
  if (props.dismissible && event.target === dialog.value) emit('close')
}
</script>

<template>
  <dialog ref="dialog" class="qg-dialog" :aria-label="title" @cancel="onCancel" @click="onClick">
    <div class="qg-dialog__body">
      <h2 class="qg-dialog__title">{{ title }}</h2>
      <slot />
    </div>
  </dialog>
</template>

<style scoped>
.qg-dialog {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface-1);
  color: var(--text-primary);
  padding: 0;
  max-width: min(640px, calc(100vw - 2rem));
  width: 100%;
}

.qg-dialog::backdrop {
  background: rgba(0, 0, 0, 0.45);
}

.qg-dialog__body {
  padding: 1.25rem 1.5rem;
}

.qg-dialog__title {
  font-size: 1.125rem;
  margin: 0 0 1rem;
}
</style>
