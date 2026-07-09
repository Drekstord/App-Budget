import { useToasts } from '../store/toasts.ts'

const ICONS = { critical: '⛔', warning: '⚠️', info: '💡' } as const

/** Alertes éphémères, annoncées aux lecteurs d'écran via aria-live. */
export function Toasts() {
  const toasts = useToasts((s) => s.toasts)
  const dismiss = useToasts((s) => s.dismiss)
  return (
    <div className="toast-stack" role="status" aria-live="polite">
      {toasts.map((t) => (
        <div key={t.key} className={`toast notice notice-${t.severity}`}>
          <span aria-hidden="true">{ICONS[t.severity]}</span>
          <span style={{ flex: 1 }}>{t.text}</span>
          <button
            type="button"
            className="icon-btn"
            style={{ minWidth: 36, minHeight: 36 }}
            aria-label="Fermer l’alerte"
            onClick={() => dismiss(t.key)}
          >
            ✕
          </button>
        </div>
      ))}
    </div>
  )
}
