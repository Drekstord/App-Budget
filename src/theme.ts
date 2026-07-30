// Palette de visualisation validée (contraste + daltonisme) — voir EXIGENCES.md.
// L'ordre des emplacements catégoriels est fixe : c'est lui qui garantit la
// séparation des couleurs adjacentes pour les daltoniens. Ne jamais le recycler.

import { useEffect, useSyncExternalStore } from 'react'
import { useStore } from './store/useStore.ts'

export const CATEGORICAL: Record<'light' | 'dark', string[]> = {
  light: ['#2a78d6', '#1baf7a', '#eda100', '#008300', '#4a3aa7', '#e34948', '#e87ba4', '#eb6834'],
  dark: ['#3987e5', '#199e70', '#c98500', '#008300', '#9085e9', '#e66767', '#d55181', '#d95926'],
}

export const STATUS = {
  good: '#0ca30c',
  warning: '#fab219',
  serious: '#ec835a',
  critical: '#d03b3b',
} as const

// Encre des graphiques alignée sur les jetons de l'interface (voir index.css).
export const CHART_INK: Record<
  'light' | 'dark',
  { text: string; muted: string; grid: string; axis: string; surface: string }
> = {
  light: { text: '#101720', muted: '#6b7583', grid: '#e3e6ea', axis: '#c8cdd4', surface: '#ffffff' },
  dark: { text: '#eef1f5', muted: '#8b95a3', grid: '#262f3a', axis: '#3a4552', surface: '#171d25' },
}

export function slotColor(slot: number, mode: 'light' | 'dark'): string {
  const palette = CATEGORICAL[mode]
  return palette[((slot - 1) % palette.length + palette.length) % palette.length]
}

const THEME_KEY = 'app-budget-theme'
const media = window.matchMedia('(prefers-color-scheme: dark)')

function subscribeMedia(cb: () => void) {
  media.addEventListener('change', cb)
  return () => media.removeEventListener('change', cb)
}

/** Mode effectif ('light' | 'dark') selon la préférence et le système. */
export function useResolvedTheme(): 'light' | 'dark' {
  const pref = useStore((s) => s.data?.settings.theme ?? 'auto')
  const systemDark = useSyncExternalStore(subscribeMedia, () => media.matches)
  const resolved = pref === 'auto' ? (systemDark ? 'dark' : 'light') : pref

  useEffect(() => {
    document.documentElement.dataset.theme = resolved
    // Persisté hors coffre (non sensible) pour appliquer le thème avant déverrouillage.
    localStorage.setItem(THEME_KEY, pref)
  }, [resolved, pref])

  return resolved
}
