// File de toasts (alertes éphémères affichées en surimpression).

import { create } from 'zustand'
import type { TransactionAlert } from '../domain/alerts.ts'

export interface Toast extends TransactionAlert {
  key: number
}

interface ToastState {
  toasts: Toast[]
  push: (alerts: TransactionAlert[]) => void
  dismiss: (key: number) => void
}

let counter = 0
const DISMISS_AFTER_MS = 8000

export const useToasts = create<ToastState>()((set) => ({
  toasts: [],
  push(alerts) {
    const stamped = alerts.map((a) => ({ ...a, key: ++counter }))
    set((s) => ({ toasts: [...s.toasts, ...stamped] }))
    for (const t of stamped) {
      setTimeout(() => {
        set((s) => ({ toasts: s.toasts.filter((x) => x.key !== t.key) }))
      }, DISMISS_AFTER_MS)
    }
  },
  dismiss(key) {
    set((s) => ({ toasts: s.toasts.filter((x) => x.key !== key) }))
  },
}))

/** Relaye aussi l'alerte en notification système si l'utilisateur l'a activé. */
export async function notifySystem(alerts: TransactionAlert[], enabled: boolean): Promise<void> {
  if (!enabled || alerts.length === 0) return
  if (typeof Notification === 'undefined' || Notification.permission !== 'granted') return
  try {
    const registration = await navigator.serviceWorker?.getRegistration()
    for (const a of alerts) {
      if (a.severity === 'info') continue
      if (registration) {
        await registration.showNotification('App Budget', { body: a.text, tag: a.id })
      } else {
        new Notification('App Budget', { body: a.text, tag: a.id })
      }
    }
  } catch {
    // Les notifications sont un bonus : ne jamais bloquer la saisie.
  }
}
