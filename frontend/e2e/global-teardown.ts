import { mergeAxeResults } from './axe-report'

export default function globalTeardown(): void {
  mergeAxeResults()
}
