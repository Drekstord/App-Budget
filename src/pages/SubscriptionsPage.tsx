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
import { todayISO } from '../domain/periods.ts'
import {
  computeCommitmentSummary,
  isCommitmentActive,
  loanRemaining,
} from '../domain/subscriptions.ts'
import { Modal } from '../components/Modal.tsx'

const monthYear = new Intl.DateTimeFormat('fr-FR', { month: 'long', year: 'numeric' })

interface FormState {
  kind: CommitmentKind
  name: string
  amount: string
  frequency: SubscriptionFrequency
  dayOfMonth: string
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
  const summary = useMemo(() => (data ? computeCommitmentSummary(data, today) : null), [data, today])

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

    const input = {
      kind: form.kind,
      name: form.name.trim(),
      amount,
      frequency: form.kind === 'loan' ? ('monthly' as const) : form.frequency,
      dayOfMonth: day,
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
    <div className="stack">
      <p className="chart-note">
        Tes abonnements et prêts prélevés automatiquement. L’app calcule ce que tu envoies chaque
        mois et compare aux budgets par catégorie.
      </p>

      {/* Synthèse */}
      <section className="card">
        <h2>Total mensuel des prélèvements</h2>
        <div className="kpi-value" style={{ marginBottom: '0.5rem' }}>
          {formatEUR(summary.totalMonthly)}
          <span className="kpi-sub" style={{ fontWeight: 400 }}> / mois</span>
        </div>
        <div className="grid-2">
          <div>
            <div className="kpi-label">Indispensables</div>
            <div className="amount">{formatEUR(summary.essentialMonthly)}</div>
          </div>
          <div>
            <div className="kpi-label">Non indispensables</div>
            <div className="amount">{formatEUR(summary.nonEssentialMonthly)}</div>
          </div>
        </div>

        {summary.byAccount.length > 0 && (
          <>
            <h3 style={{ fontSize: '0.9rem', margin: '1rem 0 0.4rem' }}>À provisionner par compte</h3>
            <ul className="list">
              {summary.byAccount.map((a) => (
                <li key={a.accountId ?? 'none'} className="list-item" style={{ minHeight: 40, padding: '0.35rem 0' }}>
                  <span className="item-body">
                    <span className="item-title" style={{ fontWeight: 500 }}>{a.name}</span>
                  </span>
                  <span className="amount">{formatEUR(a.monthly)} / mois</span>
                </li>
              ))}
            </ul>
            <p className="chart-note" style={{ marginBottom: 0 }}>
              C’est le montant à virer chaque mois sur chaque compte de prélèvement pour couvrir ses
              abonnements.
            </p>
          </>
        )}
      </section>

      {/* Comparaison au budget par catégorie */}
      {summary.byCategory.length > 0 && (
        <section className="card">
          <h2>Par catégorie vs budget</h2>
          <ul className="list">
            {summary.byCategory.map((c) => (
              <li key={c.categoryId ?? 'none'} className="list-item">
                <span className="item-icon" aria-hidden="true">
                  {c.categoryId ? (categoryById.get(c.categoryId)?.icon ?? '🏷️') : '—'}
                </span>
                <span className="item-body">
                  <span className="item-title">{c.name}</span>
                  <br />
                  <span className="item-sub">
                    {c.budget !== null
                      ? `budget ${formatEURCompact(c.budget)}`
                      : 'pas de budget défini'}
                  </span>
                </span>
                <span className="amount" style={c.over ? { color: 'var(--critical)' } : undefined}>
                  {formatEUR(c.monthly)}
                  {c.over && ' ⚠️'}
                </span>
              </li>
            ))}
          </ul>
          {summary.byCategory.some((c) => c.over) && (
            <p className="notice notice-warning" style={{ marginBottom: 0 }}>
              <span aria-hidden="true">⚠️</span> Certaines catégories : le total des abonnements
              dépasse déjà le budget mensuel.
            </p>
          )}
          <p className="chart-note" style={{ marginBottom: 0 }}>
            <Link to="/budgets">Ajuster les budgets →</Link>
          </p>
        </section>
      )}

      {/* Liste des prélèvements */}
      <section className="card">
        <h2>Mes prélèvements</h2>
        {subs.length === 0 ? (
          <p className="chart-note" style={{ margin: 0 }}>
            Aucun prélèvement pour l’instant. Ajoute ton premier abonnement ou prêt.
          </p>
        ) : (
          <ul className="list">
            {subs.map((sub) => {
              const activeNow = isCommitmentActive(sub, today)
              const cat = sub.categoryId ? categoryById.get(sub.categoryId) : null
              const acc = sub.accountId ? accountById.get(sub.accountId) : null
              const remaining = loanRemaining(sub, today)
              return (
                <li key={sub.id} className="list-item">
                  <span className="item-icon" aria-hidden="true">
                    {sub.kind === 'loan' ? '🏛️' : (cat?.icon ?? '💳')}
                  </span>
                  <span className="item-body">
                    <span className="item-title">
                      {sub.name}
                      {sub.essential && sub.kind !== 'loan' && (
                        <span className="pill-essential"> indispensable</span>
                      )}
                      {!activeNow && <span className="item-sub"> · en pause</span>}
                    </span>
                    <br />
                    <span className="item-sub">
                      {sub.kind === 'loan'
                        ? `Prêt · le ${sub.dayOfMonth} · reste ${formatEURCompact(remaining)}${sub.endDate ? ` jusqu’en ${monthYear.format(new Date(sub.endDate + 'T00:00:00'))}` : ''}`
                        : `${sub.frequency === 'yearly' ? 'Annuel' : 'Mensuel'} · le ${sub.dayOfMonth}${cat ? ` · ${cat.name}` : ''}${acc ? ` · ${acc.name}` : ''}`}
                    </span>
                  </span>
                  <span className="amount">
                    {formatEUR(sub.amount)}
                    {sub.frequency === 'yearly' && sub.kind !== 'loan' && (
                      <span className="item-sub"> /an</span>
                    )}
                  </span>
                  <button
                    type="button"
                    className="icon-btn"
                    aria-label={`Modifier ${sub.name}`}
                    onClick={() => openForm(sub)}
                  >
                    ✏️
                  </button>
                </li>
              )
            })}
          </ul>
        )}
      </section>

      <button
        type="button"
        className="fab"
        aria-label="Ajouter un prélèvement"
        onClick={() => openForm(null)}
      >
        +
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
          <div className="field">
            <span className="field-label" id="sub-kind-label">Type</span>
            <div className="chip-row" role="group" aria-labelledby="sub-kind-label">
              <button
                type="button"
                className="chip"
                aria-pressed={form.kind === 'subscription'}
                onClick={() => patch({ kind: 'subscription' })}
              >
                Abonnement
              </button>
              <button
                type="button"
                className="chip"
                aria-pressed={form.kind === 'loan'}
                onClick={() => patch({ kind: 'loan' })}
              >
                Prêt
              </button>
            </div>
          </div>

          <div className="field">
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
              <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', margin: 0 }}>
                <input
                  type="checkbox"
                  style={{ width: 'auto', minHeight: 'auto' }}
                  checked={form.essential}
                  onChange={(e) => patch({ essential: e.target.checked })}
                />
                Indispensable
              </label>
            </div>
          )}

          <div className="field">
            <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', margin: 0 }}>
              <input
                type="checkbox"
                style={{ width: 'auto', minHeight: 'auto' }}
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
          <div style={{ display: 'flex', gap: '0.5rem', marginTop: '0.5rem' }}>
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
    </div>
  )
}
