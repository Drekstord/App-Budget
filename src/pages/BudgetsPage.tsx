import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useStore } from '../store/useStore.ts'
import { alive, type Category } from '../domain/types.ts'
import { centsToInput, formatEUR, formatEURCompact, parseAmountToCents } from '../domain/money.ts'
import { periodForDate, todayISO } from '../domain/periods.ts'
import { budgetAllocation, budgetStatuses } from '../domain/stats.ts'
import { Modal } from '../components/Modal.tsx'

const dayLabel = new Intl.DateTimeFormat('fr-FR', { day: 'numeric', month: 'long' })

type SortKey = 'ratio' | 'amountDesc' | 'amountAsc' | 'spentDesc' | 'name'

const SORT_LABELS: Record<SortKey, string> = {
  ratio: '% consommé (décroissant)',
  amountDesc: 'Budget décroissant',
  amountAsc: 'Budget croissant',
  spentDesc: 'Dépensé décroissant',
  name: 'Nom (A → Z)',
}

export function BudgetsPage() {
  const data = useStore((s) => s.data)
  const setBudget = useStore((s) => s.setBudget)
  const [editingCategory, setEditingCategory] = useState<Category | null>(null)
  const [amount, setAmount] = useState('')
  const [error, setError] = useState('')
  const [sortKey, setSortKey] = useState<SortKey>('ratio')

  if (!data) return null
  const period = periodForDate(todayISO(), data.settings.monthStartDay)
  const statuses = budgetStatuses(data, period).sort((a, b) => {
    switch (sortKey) {
      case 'amountDesc':
        return b.budget.monthlyAmount - a.budget.monthlyAmount
      case 'amountAsc':
        return a.budget.monthlyAmount - b.budget.monthlyAmount
      case 'spentDesc':
        return b.spent - a.spent
      case 'name':
        return a.category.name.localeCompare(b.category.name, 'fr')
      default:
        return b.ratio - a.ratio
    }
  })
  const budgetedIds = new Set(statuses.map((s) => s.category.id))
  const unbudgeted = alive(data.categories).filter(
    (c) => c.kind === 'expense' && !c.parentId && !budgetedIds.has(c.id),
  )

  const openEditor = (category: Category, currentAmount: number | null) => {
    setEditingCategory(category)
    setAmount(currentAmount !== null ? centsToInput(currentAmount) : '')
    setError('')
  }

  const save = async () => {
    if (!editingCategory) return
    const cents = parseAmountToCents(amount)
    if (cents === null || cents <= 0) {
      setError('Saisis un montant valide, par exemple 300.')
      return
    }
    await setBudget(editingCategory.id, cents)
    setEditingCategory(null)
  }

  const remove = async () => {
    if (!editingCategory) return
    await setBudget(editingCategory.id, null)
    setEditingCategory(null)
  }

  const allocation = budgetAllocation(data)

  return (
    <div className="stack">
      <p className="chart-note">Période : {period.label}</p>

      <Link to="/prelevements" className="btn" style={{ textDecoration: 'none' }}>
        💳 Abonnements & prêts
      </Link>

      <div className="card">
        <div style={{ display: 'flex', justifyContent: 'space-between', gap: '0.5rem', flexWrap: 'wrap' }}>
          <div>
            <div className="kpi-label">Reste à attribuer</div>
            <div
              className="kpi-value"
              style={{
                color:
                  allocation.reference === 0
                    ? 'var(--text-2)'
                    : allocation.remaining < 0
                      ? 'var(--critical)'
                      : 'var(--good)',
              }}
            >
              {allocation.reference > 0 ? formatEUR(allocation.remaining) : '—'}
            </div>
          </div>
          <div style={{ textAlign: 'right', fontSize: '0.82rem', color: 'var(--text-2)' }}>
            <div>
              Revenu de référence :{' '}
              {allocation.reference > 0 ? formatEURCompact(allocation.reference) : 'non défini'}
            </div>
            <div>Déjà budgété : {formatEURCompact(allocation.totalBudgeted)}</div>
          </div>
        </div>
        {allocation.reference > 0 ? (
          <>
            <div
              className={`gauge ${allocation.remaining < 0 ? 'gauge-over' : ''}`}
              style={{ marginTop: '0.6rem' }}
              role="progressbar"
              aria-valuenow={Math.min(100, Math.round((allocation.totalBudgeted / allocation.reference) * 100))}
              aria-valuemin={0}
              aria-valuemax={100}
              aria-label="Part du revenu de référence déjà budgétée"
            >
              <span style={{ width: `${Math.min(100, (allocation.totalBudgeted / allocation.reference) * 100)}%` }} />
            </div>
            <p className="chart-note" style={{ marginBottom: 0 }}>
              {allocation.remaining < 0
                ? `Tu as budgété ${formatEUR(-allocation.remaining)} de plus que ton revenu de référence.`
                : `Il te reste ${formatEUR(allocation.remaining)} de ton revenu à répartir dans des catégories.`}{' '}
              {allocation.referenceIsManual
                ? 'Référence : revenu que tu as fixé dans les réglages.'
                : 'Référence : moyenne de tes revenus des 3 derniers mois (modifiable dans les réglages).'}
            </p>
          </>
        ) : (
          <p className="chart-note" style={{ marginBottom: 0 }}>
            Renseigne un <Link to="/reglages">revenu mensuel de référence</Link> (ou saisis des
            revenus) pour savoir combien il te reste à répartir dans tes catégories.
          </p>
        )}
      </div>

      {statuses.length > 1 && (
        <div className="field" style={{ margin: 0 }}>
          <label htmlFor="budget-sort">Trier par</label>
          <select
            id="budget-sort"
            value={sortKey}
            onChange={(e) => setSortKey(e.target.value as SortKey)}
          >
            {(Object.keys(SORT_LABELS) as SortKey[]).map((k) => (
              <option key={k} value={k}>
                {SORT_LABELS[k]}
              </option>
            ))}
          </select>
        </div>
      )}

      {statuses.length === 0 && (
        <div className="empty-state">
          <p>Aucun budget défini pour l’instant.</p>
          <p>Choisis une catégorie ci-dessous pour fixer une limite mensuelle.</p>
        </div>
      )}

      {statuses.map((s) => {
        const pct = Math.round(s.ratio * 100)
        const gaugeClass =
          s.level === 'over' ? 'gauge gauge-over' : s.level === 'warning' ? 'gauge gauge-warning' : 'gauge'
        return (
          <div key={s.budget.id} className="card">
            <div style={{ display: 'flex', alignItems: 'center', gap: '0.6rem' }}>
              <span className="item-icon" aria-hidden="true">
                {s.category.icon}
              </span>
              <div style={{ flex: 1, minWidth: 0 }}>
                <span className="item-title">{s.category.name}</span>
                <div className="item-sub">
                  {formatEUR(s.spent)} sur {formatEURCompact(s.budget.monthlyAmount)} ({pct} %)
                  {s.subscriptionSpent > 0 && (
                    <>
                      <br />
                      dont {formatEUR(s.subscriptionSpent)} d’abonnements
                    </>
                  )}
                </div>
              </div>
              <button
                type="button"
                className="icon-btn"
                aria-label={`Modifier le budget ${s.category.name}`}
                onClick={() => openEditor(s.category, s.budget.monthlyAmount)}
              >
                ✏️
              </button>
            </div>
            <div
              className={gaugeClass}
              role="progressbar"
              aria-valuenow={Math.min(pct, 100)}
              aria-valuemin={0}
              aria-valuemax={100}
              aria-label={`Budget ${s.category.name} consommé à ${pct} %`}
              style={{ marginTop: '0.6rem' }}
            >
              <span style={{ width: `${Math.min(100, pct)}%` }} />
            </div>
            {s.level === 'over' && (
              <p className="notice notice-critical" style={{ marginTop: '0.6rem', marginBottom: 0 }}>
                <span aria-hidden="true">⛔</span> Dépassé de {formatEUR(s.spent - s.budget.monthlyAmount)}.
              </p>
            )}
            {s.level !== 'over' && s.projectedOverDate && (
              <p className="notice notice-warning" style={{ marginTop: '0.6rem', marginBottom: 0 }}>
                <span aria-hidden="true">⚠️</span> À ce rythme, dépassement estimé le{' '}
                {dayLabel.format(new Date(s.projectedOverDate))}.
              </p>
            )}
          </div>
        )
      })}

      {unbudgeted.length > 0 && (
        <section className="card">
          <h2>Catégories sans budget</h2>
          <ul className="list">
            {unbudgeted.map((c) => (
              <li key={c.id} className="list-item">
                <span className="item-icon" aria-hidden="true">
                  {c.icon}
                </span>
                <span className="item-body">
                  <span className="item-title">{c.name}</span>
                </span>
                <button type="button" className="btn" onClick={() => openEditor(c, null)}>
                  Fixer un budget
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}

      <Modal
        open={editingCategory !== null}
        onClose={() => setEditingCategory(null)}
        title={editingCategory ? `Budget « ${editingCategory.name} »` : ''}
      >
        <form
          onSubmit={(e) => {
            e.preventDefault()
            void save()
          }}
        >
          <div className="field">
            <label htmlFor="budget-amount">Montant mensuel (€)</label>
            <input
              id="budget-amount"
              inputMode="decimal"
              placeholder="300"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              autoFocus
            />
          </div>
          {error && (
            <p className="pin-error" role="alert">
              {error}
            </p>
          )}
          <div style={{ display: 'flex', gap: '0.5rem' }}>
            {editingCategory && budgetedIds.has(editingCategory.id) && (
              <button type="button" className="btn btn-danger" onClick={() => void remove()}>
                Retirer
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
