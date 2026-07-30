import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useStore } from '../store/useStore.ts'
import { alive, type Category } from '../domain/types.ts'
import { centsToInput, formatEUR, formatEURCompact, parseAmountToCents } from '../domain/money.ts'
import { periodForDate, todayISO } from '../domain/periods.ts'
import { budgetAllocation, budgetStatuses, realAvailability } from '../domain/stats.ts'
import { Modal } from '../components/Modal.tsx'
import { IconCheck, IconEdit } from '../components/icons.tsx'

const dayLabel = new Intl.DateTimeFormat('fr-FR', { day: 'numeric', month: 'long' })

type SortKey = 'ratio' | 'amountDesc' | 'amountAsc' | 'spentDesc' | 'name'

const SORT_LABELS: Record<SortKey, string> = {
  ratio: '% consommé',
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
  const [sortOpen, setSortOpen] = useState(false)

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
  const real = realAvailability(data, period)

  // Barre du récap : part du revenu déjà budgétée, part libre.
  const allocPct =
    allocation.reference > 0
      ? Math.max(0, Math.min(100, (allocation.totalBudgeted / allocation.reference) * 100))
      : 0
  const overAllocated = allocation.reference > 0 && allocation.remaining < 0

  return (
    <>
      {/* 1 — Un seul récap : combien du revenu est déjà engagé, combien reste libre. */}
      <section className="card" aria-label="Répartition du revenu">
        <span className="label">Reste à attribuer</span>
        <p
          className={`hero hero-sm ${
            allocation.reference === 0 ? '' : overAllocated ? 'hero-critical' : 'hero-good'
          }`}
        >
          {allocation.reference > 0 ? formatEUR(allocation.remaining) : '—'}
        </p>

        {allocation.reference > 0 ? (
          <>
            <div
              className="split"
              role="img"
              aria-label={`${formatEUR(allocation.totalBudgeted)} budgétés sur un revenu de référence de ${formatEUR(
                allocation.reference,
              )}`}
            >
              <i
                className={overAllocated ? 'seg-over' : 'seg-spent'}
                style={{ width: `${allocPct}%` }}
              />
            </div>
            <ul className="legend">
              <li>
                <span
                  className="swatch"
                  style={{ background: overAllocated ? 'var(--critical)' : 'var(--accent)' }}
                />
                Budgété {formatEURCompact(allocation.totalBudgeted)}
              </li>
              <li>
                <span className="swatch" style={{ background: 'var(--sunk)' }} />
                Revenu {formatEURCompact(allocation.reference)}
              </li>
            </ul>
            <p className="hint">
              {overAllocated
                ? `Tu as budgété ${formatEUR(-allocation.remaining)} de plus que ton revenu de référence.`
                : `Il reste ${formatEUR(allocation.remaining)} de ton revenu à répartir.`}{' '}
              {allocation.referenceIsManual
                ? 'Référence fixée dans les réglages.'
                : 'Référence : moyenne des 3 derniers mois.'}
            </p>
          </>
        ) : (
          <p className="hint">
            Renseigne un <Link to="/reglages">revenu mensuel de référence</Link> pour savoir combien
            il te reste à répartir.
          </p>
        )}

        {real.reference > 0 && (
          <details className="data-table" style={{ marginTop: '0.6rem' }}>
            <summary>
              Disponible réel : {formatEUR(real.realRemaining)}
              {real.spentUnbudgeted > 0 && ` (dont ${formatEUR(real.spentUnbudgeted)} hors budget)`}
            </summary>
            <div className="table-wrap">
              <table className="data">
                <caption className="visually-hidden">Détail du disponible réel</caption>
                <tbody>
                  <tr>
                    <th scope="row">Revenu de référence</th>
                    <td className="num">{formatEUR(real.reference)}</td>
                  </tr>
                  <tr>
                    <th scope="row">− Dépensé dans les budgets</th>
                    <td className="num">{formatEUR(real.spentBudgeted)}</td>
                  </tr>
                  <tr style={real.spentUnbudgeted > 0 ? { color: 'var(--critical)' } : undefined}>
                    <th scope="row">− Dépensé hors budget</th>
                    <td className="num">{formatEUR(real.spentUnbudgeted)}</td>
                  </tr>
                  <tr>
                    <th scope="row">− Encore réservé dans les budgets</th>
                    <td className="num">{formatEUR(real.budgetReserved)}</td>
                  </tr>
                  <tr>
                    <th scope="row">
                      <strong>= Disponible réel</strong>
                    </th>
                    <td className="num">
                      <strong>{formatEUR(real.realRemaining)}</strong>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </details>
        )}
      </section>

      {/* 2 — Toutes les enveloppes dans une seule carte, une ligne chacune. */}
      <section className="card" aria-label="Enveloppes du mois">
        <div className="row-between" style={{ alignItems: 'center', marginBottom: '0.2rem' }}>
          <span className="label">Enveloppes</span>
          {statuses.length > 1 && (
            <button type="button" className="btn-ghost btn-sm" onClick={() => setSortOpen(true)}>
              {SORT_LABELS[sortKey]} ⌄
            </button>
          )}
        </div>

        {statuses.length === 0 ? (
          <p className="hint" style={{ marginTop: 0 }}>
            Aucune enveloppe pour l’instant. Choisis une catégorie ci-dessous pour lui fixer une
            limite mensuelle.
          </p>
        ) : (
          <ul className="rows">
            {statuses.map((s) => {
              const pct = Math.round(s.ratio * 100)
              const meterClass =
                s.level === 'over' ? 'meter meter-over' : s.level === 'warning' ? 'meter meter-warning' : 'meter'
              const alert =
                s.level === 'over'
                  ? `Dépassé de ${formatEUR(s.spent - s.budget.monthlyAmount)}`
                  : s.projectedOverDate
                    ? `Dépassement estimé le ${dayLabel.format(new Date(s.projectedOverDate))}`
                    : null
              return (
                <li key={s.budget.id} className="env">
                  <button
                    type="button"
                    className="env-btn"
                    onClick={() => openEditor(s.category, s.budget.monthlyAmount)}
                    aria-label={`${s.category.name} : ${formatEUR(s.spent)} dépensés sur ${formatEUR(
                      s.budget.monthlyAmount,
                    )}, soit ${pct} %. Modifier l’enveloppe.`}
                  >
                    <span className="env-head">
                      <span className="glyph glyph-sm" aria-hidden="true">
                        {s.category.icon}
                      </span>
                      <span className="row-main">
                        <span className="row-title">{s.category.name}</span>
                        {s.subscriptionSpent > 0 && (
                          <span className="row-meta">
                            dont {formatEURCompact(s.subscriptionSpent)} de prélèvements
                          </span>
                        )}
                      </span>
                      <span className="amount env-amount">
                        {formatEUR(s.spent)}
                        <span className="env-cap"> / {formatEURCompact(s.budget.monthlyAmount)}</span>
                      </span>
                      {/* Le pourcentage n'apparaît que s'il mérite l'attention. */}
                      {s.level !== 'ok' && (
                        <span className={`pill ${s.level === 'over' ? 'pill-over' : 'pill-warning'}`}>
                          {pct} %
                        </span>
                      )}
                      <IconEdit className="chev" size={16} />
                    </span>
                    <span className={meterClass} aria-hidden="true">
                      <span style={{ width: `${Math.min(100, pct)}%` }} />
                    </span>
                    {alert && (
                      <span
                        className={`env-alert ${s.level === 'over' ? 'is-over' : 'is-warning'}`}
                      >
                        {alert}
                      </span>
                    )}
                  </button>
                </li>
              )
            })}
          </ul>
        )}
      </section>

      {/* 3 — Ce qui est dépensé (ou dépensable) sans enveloppe. */}
      {(real.unbudgeted.length > 0 || unbudgeted.length > 0) && (
        <section className="card" aria-label="Hors enveloppe">
          <span className="label" style={{ marginBottom: '0.2rem' }}>
            Hors enveloppe
          </span>
          {real.unbudgeted.length > 0 && (
            <p className="hint" style={{ marginTop: 0, marginBottom: '0.3rem' }}>
              Dépensé ce mois sans enveloppe : ces montants réduisent ton disponible sans apparaître
              dans les jauges.
            </p>
          )}
          <ul className="rows">
            {real.unbudgeted.map((u) => (
              <li key={u.category?.id ?? 'none'} className="row">
                <span className="glyph glyph-sm" aria-hidden="true">
                  {u.category?.icon ?? '❓'}
                </span>
                <span className="row-main">
                  <span className="row-title">{u.category?.name ?? 'Sans catégorie'}</span>
                </span>
                <span className="amount" style={{ color: 'var(--critical)' }}>
                  {formatEUR(u.amount)}
                </span>
                {u.category && (
                  <button
                    type="button"
                    className="btn btn-sm"
                    onClick={() => openEditor(u.category!, null)}
                  >
                    Budgéter
                  </button>
                )}
              </li>
            ))}
          </ul>

          {unbudgeted.length > 0 && (
            <details style={{ marginTop: real.unbudgeted.length > 0 ? '0.7rem' : 0 }}>
              <summary className="more-summary">
                {unbudgeted.length} catégorie{unbudgeted.length > 1 ? 's' : ''} sans enveloppe
              </summary>
              <ul className="rows">
                {unbudgeted.map((c) => (
                  <li key={c.id} className="row">
                    <span className="glyph glyph-sm" aria-hidden="true">
                      {c.icon}
                    </span>
                    <span className="row-main">
                      <span className="row-title">{c.name}</span>
                    </span>
                    <button
                      type="button"
                      className="btn btn-sm"
                      onClick={() => openEditor(c, null)}
                    >
                      Budgéter
                    </button>
                  </li>
                ))}
              </ul>
            </details>
          )}
        </section>
      )}

      <Modal open={sortOpen} onClose={() => setSortOpen(false)} title="Trier les enveloppes">
        <ul className="rows">
          {(Object.keys(SORT_LABELS) as SortKey[]).map((k) => (
            <li key={k} className="row" style={{ minHeight: 48, padding: 0 }}>
              <button
                type="button"
                className="row-btn"
                aria-pressed={sortKey === k}
                onClick={() => {
                  setSortKey(k)
                  setSortOpen(false)
                }}
              >
                <span className="row-main">
                  <span className="row-title">{SORT_LABELS[k]}</span>
                </span>
                {sortKey === k && <IconCheck className="chev" />}
              </button>
            </li>
          ))}
        </ul>
      </Modal>

      <Modal
        open={editingCategory !== null}
        onClose={() => setEditingCategory(null)}
        title={editingCategory ? `Enveloppe « ${editingCategory.name} »` : ''}
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
    </>
  )
}
