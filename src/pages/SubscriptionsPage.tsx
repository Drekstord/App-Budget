import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { useStore } from '../store/useStore.ts'
import {
  alive,
  type CommitmentKind,
  type Subscription,
  type SubscriptionFrequency,
} from '../domain/types.ts'
import { centsToInput, formatEUR, formatEURCompact, parseAmountToCents } from '../domain/money.ts'
import { periodForDate, todayISO } from '../domain/periods.ts'
import {
  computeCommitmentSummary,
  isCommitmentActive,
  loanRemaining,
} from '../domain/subscriptions.ts'
import { nextOccurrence } from '../domain/recurring.ts'
import { Modal } from '../components/Modal.tsx'
import { IconEdit, IconPlus } from '../components/icons.tsx'

const monthYear = new Intl.DateTimeFormat('fr-FR', { month: 'long', year: 'numeric' })

const MONTH_NAMES = [
  'janvier',
  'février',
  'mars',
  'avril',
  'mai',
  'juin',
  'juillet',
  'août',
  'septembre',
  'octobre',
  'novembre',
  'décembre',
]

interface FormState {
  kind: CommitmentKind
  name: string
  amount: string
  frequency: SubscriptionFrequency
  dayOfMonth: string
  dueMonth: string
  categoryId: string
  essential: boolean
  accountId: string
  endDate: string
  active: boolean
}

const EMPTY_FORM: FormState = {
  kind: 'subscription',
  name: '',
  amount: '',
  frequency: 'monthly',
  dayOfMonth: '1',
  dueMonth: String(new Date().getMonth() + 1),
  categoryId: '',
  essential: false,
  accountId: '',
  endDate: '',
  active: true,
}

export function SubscriptionsPage() {
  const data = useStore((s) => s.data)
  const addSubscription = useStore((s) => s.addSubscription)
  const updateSubscription = useStore((s) => s.updateSubscription)
  const deleteSubscription = useStore((s) => s.deleteSubscription)

  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<Subscription | null>(null)
  const [form, setForm] = useState<FormState>(EMPTY_FORM)
  const [error, setError] = useState('')

  const today = todayISO()
  const period = data ? periodForDate(today, data.settings.monthStartDay) : null
  const summary = useMemo(
    () => (data && period ? computeCommitmentSummary(data, today, period) : null),
    [data, today, period],
  )

  if (!data || !summary) return null
  const categories = alive(data.categories).filter((c) => c.kind === 'expense')
  const accounts = alive(data.accounts).filter((a) => !a.archived)
  const categoryById = new Map(alive(data.categories).map((c) => [c.id, c]))
  const accountById = new Map(accounts.map((a) => [a.id, a]))
  const subs = alive(data.subscriptions).sort(
    (a, b) => Number(b.active) - Number(a.active) || a.name.localeCompare(b.name),
  )

  const patch = (p: Partial<FormState>) => setForm((f) => ({ ...f, ...p }))

  const openForm = (sub: Subscription | null) => {
    setEditing(sub)
    setError('')
    if (sub) {
      setForm({
        kind: sub.kind,
        name: sub.name,
        amount: centsToInput(sub.amount),
        frequency: sub.frequency,
        dayOfMonth: String(sub.dayOfMonth),
        dueMonth: String(sub.dueMonth ?? new Date().getMonth() + 1),
        categoryId: sub.categoryId ?? '',
        essential: sub.essential,
        accountId: sub.accountId ?? '',
        endDate: sub.endDate ?? '',
        active: sub.active,
      })
    } else {
      setForm({ ...EMPTY_FORM, accountId: data.settings.defaultAccountId ?? '' })
    }
    setOpen(true)
  }

  const save = async () => {
    const amount = parseAmountToCents(form.amount)
    if (!form.name.trim()) return setError('Donne un nom à ce prélèvement.')
    if (amount === null || amount <= 0) return setError('Indique un montant valide.')
    const day = Math.min(31, Math.max(1, Number(form.dayOfMonth) || 1))
    if (form.kind === 'loan' && !form.endDate) return setError('Indique la date de fin du prêt.')

    const isYearly = form.kind === 'subscription' && form.frequency === 'yearly'
    const input = {
      kind: form.kind,
      name: form.name.trim(),
      amount,
      frequency: form.kind === 'loan' ? ('monthly' as const) : form.frequency,
      dayOfMonth: day,
      dueMonth: isYearly ? Math.min(12, Math.max(1, Number(form.dueMonth) || 1)) : null,
      categoryId: form.categoryId || null,
      essential: form.kind === 'loan' ? true : form.essential,
      accountId: form.accountId || null,
      active: form.active,
      endDate: form.kind === 'loan' ? form.endDate : null,
    }
    if (editing) await updateSubscription(editing.id, input)
    else await addSubscription(input)
    setOpen(false)
  }

  const remove = async () => {
    if (!editing) return
    if (!window.confirm(`Supprimer « ${editing.name} » ?`)) return
    await deleteSubscription(editing.id)
    setOpen(false)
  }

  return (
    <>
      {/* Synthèse */}
      <section className="card" aria-label="Total mensuel des prélèvements">
        <span className="label">Prélevé chaque mois</span>
        <p className="hero hero-sm">{formatEUR(summary.totalMonthly)}</p>
        <p className="hint">
          À chaque échéance, l’app crée l’opération dans{' '}
          <Link to="/transactions">Opérations</Link> : c’est elle qui alimente le budget.
        </p>
        <ul className="legend" style={{ marginTop: '0.7rem' }}>
          <li>
            <span className="swatch" style={{ background: 'var(--accent)' }} />
            Indispensables {formatEURCompact(summary.essentialMonthly)}
          </li>
          <li>
            <span className="swatch" style={{ background: 'var(--accent-soft)' }} />
            Optionnels {formatEURCompact(summary.nonEssentialMonthly)}
          </li>
        </ul>
        {summary.totalMonthly > 0 && (
          <div
            className="split"
            role="img"
            aria-label={`${formatEUR(summary.essentialMonthly)} d’indispensables sur ${formatEUR(
              summary.totalMonthly,
            )}`}
          >
            <i
              className="seg-spent"
              style={{ width: `${(summary.essentialMonthly / summary.totalMonthly) * 100}%` }}
            />
            <i
              className="seg-reserved"
              style={{ width: `${(summary.nonEssentialMonthly / summary.totalMonthly) * 100}%` }}
            />
          </div>
        )}

        {summary.byAccount.length > 0 && (
          <details style={{ marginTop: '0.5rem' }}>
            <summary className="more-summary">À provisionner par compte</summary>
            <ul className="rows">
              {summary.byAccount.map((a) => (
                <li key={a.accountId ?? 'none'} className="row" style={{ minHeight: 42 }}>
                  <span className="row-main">
                    <span className="row-title">{a.name}</span>
                  </span>
                  <span className="amount">{formatEUR(a.monthly)}</span>
                </li>
              ))}
            </ul>
            <p className="hint">
              Montant à virer chaque mois sur chaque compte pour couvrir ses prélèvements.
            </p>
          </details>
        )}
      </section>

      {/* Comparaison au budget par catégorie */}
      {summary.byCategory.length > 0 && (
        <section className="card" aria-label="Prélèvements par catégorie">
          <span className="label" style={{ marginBottom: '0.2rem' }}>
            Par catégorie
          </span>
          <ul className="rows">
            {summary.byCategory.map((c) => (
              <li key={c.categoryId ?? 'none'} className="row">
                <span className="glyph glyph-sm" aria-hidden="true">
                  {c.categoryId ? (categoryById.get(c.categoryId)?.icon ?? '🏷️') : '—'}
                </span>
                <span className="row-main">
                  <span className="row-title">{c.name}</span>
                  <span className="row-meta">
                    {c.budget !== null
                      ? `budget ${formatEURCompact(c.budget)}`
                      : 'pas de budget défini'}
                  </span>
                </span>
                <span className="amount" style={c.over ? { color: 'var(--critical)' } : undefined}>
                  {formatEUR(c.monthly)}
                </span>
                {c.over && <span className="pill pill-over">dépasse</span>}
              </li>
            ))}
          </ul>
          <p className="hint">
            Prévisionnel de {period?.label} : un abonnement annuel compte en totalité dans son mois
            d’échéance. · <Link to="/budgets">Ajuster les budgets</Link>
          </p>
        </section>
      )}

      {/* Liste des prélèvements */}
      <section className="card" aria-label="Mes prélèvements">
        <span className="label" style={{ marginBottom: '0.2rem' }}>
          Mes prélèvements
        </span>
        {subs.length === 0 ? (
          <p className="hint" style={{ marginTop: 0 }}>
            Aucun prélèvement pour l’instant. Ajoute ton premier abonnement ou prêt.
          </p>
        ) : (
          <ul className="rows">
            {subs.map((sub) => {
              const activeNow = isCommitmentActive(sub, today)
              const cat = sub.categoryId ? categoryById.get(sub.categoryId) : null
              const acc = sub.accountId ? accountById.get(sub.accountId) : null
              const remaining = loanRemaining(sub, today)
              return (
                <li key={sub.id} className="row" style={{ padding: 0 }}>
                  <button
                    type="button"
                    className="row-btn"
                    aria-label={`Modifier ${sub.name}, ${formatEUR(sub.amount)}`}
                    onClick={() => openForm(sub)}
                  >
                    <span className="glyph" aria-hidden="true">
                      {sub.kind === 'loan' ? '🏛️' : (cat?.icon ?? '💳')}
                    </span>
                    <span className="row-main">
                      <span className="row-title">
                        {activeNow && nextOccurrence(sub, today) && (
                          <span className="visually-hidden">
                            Prochaine échéance le {nextOccurrence(sub, today)}.{' '}
                          </span>
                        )}
                        {sub.name}
                        {sub.essential && sub.kind !== 'loan' && (
                          <span className="pill pill-accent row-badge">indispensable</span>
                        )}
                        {!activeNow && <span className="pill row-badge">en pause</span>}
                      </span>
                      <span className="row-meta">
                        {sub.kind === 'loan'
                          ? `Prêt · le ${sub.dayOfMonth} · reste ${formatEURCompact(remaining)}${sub.endDate ? ` jusqu’en ${monthYear.format(new Date(sub.endDate + 'T00:00:00'))}` : ''}`
                          : `${
                              sub.frequency === 'yearly'
                                ? `Annuel · le ${sub.dayOfMonth} ${MONTH_NAMES[(sub.dueMonth ?? 1) - 1]}`
                                : `Mensuel · le ${sub.dayOfMonth}`
                            }${cat ? ` · ${cat.name}` : ''}${acc ? ` · ${acc.name}` : ''}`}
                      </span>
                    </span>
                    <span className="amount">
                      {formatEUR(sub.amount)}
                      {sub.frequency === 'yearly' && sub.kind !== 'loan' && (
                        <span className="env-cap"> /an</span>
                      )}
                    </span>
                    <IconEdit className="chev" size={16} />
                  </button>
                </li>
              )
            })}
          </ul>
        )}
      </section>

      <button type="button" className="btn btn-primary" onClick={() => openForm(null)}>
        <IconPlus size={18} /> Ajouter un prélèvement
      </button>

      <Modal
        open={open}
        onClose={() => setOpen(false)}
        title={editing ? 'Modifier le prélèvement' : 'Nouveau prélèvement'}
      >
        <form
          onSubmit={(e) => {
            e.preventDefault()
            void save()
          }}
        >
          <div className="segmented" role="group" aria-label="Type de prélèvement">
            <button
              type="button"
              aria-pressed={form.kind === 'subscription'}
              onClick={() => patch({ kind: 'subscription' })}
            >
              Abonnement
            </button>
            <button
              type="button"
              aria-pressed={form.kind === 'loan'}
              onClick={() => patch({ kind: 'loan' })}
            >
              Prêt
            </button>
          </div>

          <div className="field" style={{ marginTop: '0.85rem' }}>
            <label htmlFor="sub-name">Nom</label>
            <input
              id="sub-name"
              value={form.name}
              onChange={(e) => patch({ name: e.target.value })}
              placeholder={form.kind === 'loan' ? 'Prêt auto, crédit conso…' : 'Netflix, assurance…'}
              autoFocus
            />
          </div>

          <div className="grid-2">
            <div className="field">
              <label htmlFor="sub-amount">
                {form.kind === 'loan' ? 'Mensualité (€)' : 'Montant (€)'}
              </label>
              <input
                id="sub-amount"
                inputMode="decimal"
                value={form.amount}
                onChange={(e) => patch({ amount: e.target.value })}
                placeholder="0,00"
              />
            </div>
            <div className="field">
              <label htmlFor="sub-day">Jour de prélèvement</label>
              <input
                id="sub-day"
                inputMode="numeric"
                value={form.dayOfMonth}
                onChange={(e) => patch({ dayOfMonth: e.target.value })}
                placeholder="1"
              />
            </div>
          </div>

          {form.kind === 'subscription' ? (
            <>
              <div className="field">
                <label htmlFor="sub-freq">Fréquence</label>
                <select
                  id="sub-freq"
                  value={form.frequency}
                  onChange={(e) => patch({ frequency: e.target.value as SubscriptionFrequency })}
                >
                  <option value="monthly">Mensuelle</option>
                  <option value="yearly">Annuelle</option>
                </select>
              </div>
              {form.frequency === 'yearly' && (
                <div className="field">
                  <label htmlFor="sub-month">Mois du prélèvement</label>
                  <select
                    id="sub-month"
                    value={form.dueMonth}
                    onChange={(e) => patch({ dueMonth: e.target.value })}
                  >
                    {MONTH_NAMES.map((m, i) => (
                      <option key={m} value={i + 1}>
                        {m}
                      </option>
                    ))}
                  </select>
                  <p className="hint">
                    Le montant complet sera compté dans le budget de ce mois-là, quand l’échéance
                    arrive.
                  </p>
                </div>
              )}
            </>
          ) : (
            <div className="field">
              <label htmlFor="sub-end">Dernière mensualité (date de fin)</label>
              <input
                id="sub-end"
                type="date"
                value={form.endDate}
                onChange={(e) => patch({ endDate: e.target.value })}
              />
            </div>
          )}

          <div className="field">
            <label htmlFor="sub-cat">Catégorie</label>
            <select
              id="sub-cat"
              value={form.categoryId}
              onChange={(e) => patch({ categoryId: e.target.value })}
            >
              <option value="">— Aucune —</option>
              {categories.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </select>
          </div>

          <div className="field">
            <label htmlFor="sub-account">Compte de prélèvement</label>
            <select
              id="sub-account"
              value={form.accountId}
              onChange={(e) => patch({ accountId: e.target.value })}
            >
              <option value="">— Aucun —</option>
              {accounts.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.name}
                </option>
              ))}
            </select>
          </div>

          {form.kind === 'subscription' && (
            <div className="field">
              <label className="field-inline">
                <input
                  type="checkbox"
                  checked={form.essential}
                  onChange={(e) => patch({ essential: e.target.checked })}
                />
                Indispensable
              </label>
            </div>
          )}

          <div className="field">
            <label className="field-inline">
              <input
                type="checkbox"
                checked={form.active}
                onChange={(e) => patch({ active: e.target.checked })}
              />
              En cours (décoche pour mettre en pause)
            </label>
          </div>

          {error && (
            <p className="pin-error" role="alert">
              {error}
            </p>
          )}
          <div className="sheet-actions">
            {editing && (
              <button type="button" className="btn btn-danger" onClick={() => void remove()}>
                Supprimer
              </button>
            )}
            <button type="submit" className="btn btn-primary" style={{ flex: 1 }}>
              Enregistrer
            </button>
          </div>
        </form>
      </Modal>
    </>
  )
}
