import { useMemo, useState } from 'react'
import { useStore } from '../store/useStore.ts'
import { alive, type Transaction } from '../domain/types.ts'
import { formatEUR } from '../domain/money.ts'
import { inPeriod, periodForDate, todayISO } from '../domain/periods.ts'
import { TransactionForm } from '../components/TransactionForm.tsx'

const dateHeading = new Intl.DateTimeFormat('fr-FR', {
  weekday: 'long',
  day: 'numeric',
  month: 'long',
})

type PeriodFilter = 'current' | '90d' | 'all'

export function TransactionsPage() {
  const data = useStore((s) => s.data)
  const [search, setSearch] = useState('')
  const [periodFilter, setPeriodFilter] = useState<PeriodFilter>('current')
  const [categoryFilter, setCategoryFilter] = useState('')
  const [accountFilter, setAccountFilter] = useState('')
  const [formOpen, setFormOpen] = useState(false)
  const [editing, setEditing] = useState<Transaction | undefined>()

  const filtered = useMemo(() => {
    if (!data) return []
    const period = periodForDate(todayISO(), data.settings.monthStartDay)
    const cutoff90 = new Date()
    cutoff90.setDate(cutoff90.getDate() - 90)
    const cutoffISO = cutoff90.toISOString().slice(0, 10)
    const needle = search.trim().toLowerCase()

    return alive(data.transactions)
      .filter((t) => {
        if (periodFilter === 'current' && !inPeriod(t.date, period)) return false
        if (periodFilter === '90d' && t.date < cutoffISO) return false
        if (categoryFilter && t.categoryId !== categoryFilter) return false
        if (accountFilter && t.accountId !== accountFilter && t.toAccountId !== accountFilter)
          return false
        if (needle) {
          const cat = data.categories.find((c) => c.id === t.categoryId)
          const haystack = `${t.note} ${t.payee} ${cat?.name ?? ''}`.toLowerCase()
          if (!haystack.includes(needle)) return false
        }
        return true
      })
      .sort((a, b) => (a.date === b.date ? b.createdAt.localeCompare(a.createdAt) : b.date.localeCompare(a.date)))
  }, [data, search, periodFilter, categoryFilter, accountFilter])

  if (!data) return null
  const categories = alive(data.categories)
  const accounts = alive(data.accounts)
  const categoryById = new Map(categories.map((c) => [c.id, c]))
  const accountById = new Map(accounts.map((a) => [a.id, a]))

  const groups: { date: string; items: Transaction[] }[] = []
  for (const t of filtered) {
    const last = groups[groups.length - 1]
    if (last && last.date === t.date) last.items.push(t)
    else groups.push({ date: t.date, items: [t] })
  }

  const describe = (t: Transaction): { icon: string; title: string; sub: string } => {
    if (t.type === 'transfer') {
      const from = accountById.get(t.accountId)?.name ?? '?'
      const to = accountById.get(t.toAccountId ?? '')?.name ?? '?'
      return { icon: '🔁', title: t.note || 'Virement', sub: `${from} → ${to}` }
    }
    const cat = categoryById.get(t.categoryId ?? '')
    return {
      icon: cat?.icon ?? '❓',
      title: t.payee || cat?.name || 'Sans catégorie',
      sub: [cat?.name, accountById.get(t.accountId)?.name, t.note].filter(Boolean).join(' · '),
    }
  }

  return (
    <div className="stack">
      <div className="filters-row" role="search">
        <input
          type="search"
          placeholder="Rechercher…"
          aria-label="Rechercher dans les opérations"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
        <select
          aria-label="Filtrer par période"
          value={periodFilter}
          onChange={(e) => setPeriodFilter(e.target.value as PeriodFilter)}
        >
          <option value="current">Mois en cours</option>
          <option value="90d">3 derniers mois</option>
          <option value="all">Tout</option>
        </select>
        <select
          aria-label="Filtrer par catégorie"
          value={categoryFilter}
          onChange={(e) => setCategoryFilter(e.target.value)}
        >
          <option value="">Toutes catégories</option>
          {categories.map((c) => (
            <option key={c.id} value={c.id}>
              {c.name}
            </option>
          ))}
        </select>
        <select
          aria-label="Filtrer par compte"
          value={accountFilter}
          onChange={(e) => setAccountFilter(e.target.value)}
        >
          <option value="">Tous comptes</option>
          {accounts.map((a) => (
            <option key={a.id} value={a.id}>
              {a.name}
            </option>
          ))}
        </select>
      </div>

      {groups.length === 0 ? (
        <div className="empty-state">
          <p>Aucune opération pour ces critères.</p>
          <p>Ajoute ta première dépense avec le bouton +.</p>
        </div>
      ) : (
        groups.map((group) => (
          <section key={group.date} aria-label={dateHeading.format(new Date(group.date))}>
            <p className="date-heading">{dateHeading.format(new Date(group.date))}</p>
            <ul className="list card" style={{ padding: '0 0.75rem' }}>
              {group.items.map((t) => {
                const d = describe(t)
                const sign = t.type === 'expense' ? '-' : t.type === 'income' ? '+' : ''
                return (
                  <li key={t.id} className="list-item">
                    <span className="item-icon" aria-hidden="true">
                      {d.icon}
                    </span>
                    <span className="item-body">
                      <span className="item-title">{d.title}</span>
                      <br />
                      <span className="item-sub">{d.sub}</span>
                    </span>
                    <span
                      className={`amount ${t.type === 'income' ? 'amount-positive' : 'amount-negative'}`}
                    >
                      {sign}
                      {formatEUR(t.amount)}
                    </span>
                    <button
                      type="button"
                      className="icon-btn"
                      aria-label={`Modifier : ${d.title}, ${formatEUR(t.amount)}`}
                      onClick={() => {
                        setEditing(t)
                        setFormOpen(true)
                      }}
                    >
                      ✏️
                    </button>
                  </li>
                )
              })}
            </ul>
          </section>
        ))
      )}

      <button
        type="button"
        className="fab"
        aria-label="Ajouter une opération"
        onClick={() => {
          setEditing(undefined)
          setFormOpen(true)
        }}
      >
        +
      </button>

      <TransactionForm open={formOpen} onClose={() => setFormOpen(false)} transaction={editing} />
    </div>
  )
}
