import { Link } from 'react-router-dom'
import { useStore } from '../store/useStore.ts'
import { useResolvedTheme } from '../theme.ts'
import { alive } from '../domain/types.ts'
import { formatEUR, formatEURCompact } from '../domain/money.ts'
import { periodForDate, periodProgress, todayISO } from '../domain/periods.ts'
import {
  accountBalance,
  budgetStatuses,
  computeKpis,
  expensesByRootCategory,
  periodSeries,
  realAvailability,
} from '../domain/stats.ts'
import { computeCommitmentSummary } from '../domain/subscriptions.ts'
import { computeAdvice, type AdviceSeverity } from '../domain/advice.ts'
import { BudgetVsActual, CategoryDonut, MonthlyBars } from '../components/charts.tsx'
import {
  IconAlert,
  IconCard,
  IconCheckCircle,
  IconChevronRight,
  IconInfo,
} from '../components/icons.tsx'

const SEVERITY_ICON: Record<AdviceSeverity, typeof IconAlert> = {
  critical: IconAlert,
  warning: IconAlert,
  info: IconInfo,
  good: IconCheckCircle,
}

export function DashboardPage() {
  const data = useStore((s) => s.data)
  const mode = useResolvedTheme()

  if (!data) return null
  const today = todayISO()
  const period = periodForDate(today, data.settings.monthStartDay)
  const accounts = alive(data.accounts).filter((a) => !a.archived)
  const kpis = computeKpis(data, period)
  const real = realAvailability(data, period, today)
  const slices = expensesByRootCategory(data, period)
  const series = periodSeries(data, 6)
  const statuses = budgetStatuses(data, period)
  const advice = computeAdvice(data)
  const commitments = computeCommitmentSummary(data, today)

  // Barre de répartition : dépensé, encore réservé, libre — sur la base du
  // revenu de référence. Sans revenu connu, on se rabat sur les dépenses seules.
  const base = real.reference > 0 ? real.reference : real.totalSpent + real.budgetReserved
  const pct = (v: number) => (base > 0 ? Math.max(0, Math.min(100, (v / base) * 100)) : 0)
  const overspent = real.reference > 0 && real.realRemaining < 0

  // Jours restants avant la prochaine période (donc, si le mois est calé sur la
  // paie, avant le prochain salaire) et budget quotidien correspondant.
  const { totalDays, elapsedDays } = periodProgress(period, today)
  const daysLeft = totalDays - elapsedDays
  const perDay = daysLeft > 0 && real.realRemaining > 0 ? Math.floor(real.realRemaining / daysLeft) : 0

  return (
    <>
      {/* Le chiffre qui répond à « combien puis-je encore dépenser ? » */}
      <section className="card" aria-label="Disponible ce mois">
        <span className="label">{real.reference > 0 ? 'Disponible réel' : 'Dépensé ce mois'}</span>
        <p className={`hero ${overspent ? 'hero-critical' : ''}`}>
          {formatEUR(real.reference > 0 ? real.realRemaining : real.totalSpent)}
        </p>
        <p className="hint" style={{ margin: 0 }}>
          {real.reference > 0
            ? 'après dépenses et enveloppes réservées'
            : 'définis un revenu de référence dans les Réglages pour voir ton disponible'}
          {/* Quand le mois est calé sur la paie, « combien de jours à tenir »
              compte autant que « combien il reste ». */}
          {daysLeft > 0 && ` · ${daysLeft} jour${daysLeft > 1 ? 's' : ''} à tenir`}
          {daysLeft > 0 && perDay > 0 && `, soit ${formatEURCompact(perDay)}/jour`}
        </p>

        {base > 0 && (
          <>
            <div
              className="split"
              role="img"
              aria-label={`Répartition : ${formatEUR(real.totalSpent)} dépensés, ${formatEUR(
                real.budgetReserved,
              )} réservés, ${formatEUR(Math.max(0, real.realRemaining))} libres`}
            >
              <i
                className={overspent ? 'seg-over' : 'seg-spent'}
                style={{ width: `${pct(real.totalSpent)}%` }}
              />
              <i className="seg-reserved" style={{ width: `${pct(real.budgetReserved)}%` }} />
            </div>
            <ul className="legend">
              <li>
                <span className="swatch" style={{ background: 'var(--accent)' }} />
                Dépensé {formatEURCompact(real.totalSpent)}
              </li>
              <li>
                <span className="swatch" style={{ background: 'var(--accent-soft)' }} />
                Réservé {formatEURCompact(real.budgetReserved)}
              </li>
              {real.reference > 0 && (
                <li>
                  <span className="swatch" style={{ background: 'var(--sunk)' }} />
                  Libre {formatEURCompact(Math.max(0, real.realRemaining))}
                </li>
              )}
            </ul>
          </>
        )}
      </section>

      <div className="tiles">
        <div className="tile">
          <span className="label">Hors budget</span>
          <div
            className="tile-v"
            style={real.spentUnbudgeted > 0 ? { color: 'var(--critical)' } : undefined}
          >
            {formatEUR(real.spentUnbudgeted)}
          </div>
          <div className="tile-d">
            {real.unbudgeted.length === 0
              ? 'tout est budgété'
              : `${real.unbudgeted.length} catégorie${real.unbudgeted.length > 1 ? 's' : ''}`}
          </div>
        </div>
        <div className="tile">
          <span className="label">Épargne</span>
          <div className="tile-v">{kpis.savingsRate === null ? '—' : `${kpis.savingsRate} %`}</div>
          <div className="tile-d">des revenus du mois</div>
        </div>
        <div className="tile">
          <span className="label">Solde total</span>
          <div
            className="tile-v"
            style={kpis.totalBalance < 0 ? { color: 'var(--critical)' } : undefined}
          >
            {formatEUR(kpis.totalBalance)}
          </div>
          <div className="tile-d">tous comptes</div>
        </div>
        <div className="tile">
          <span className="label">Dépenses</span>
          <div className="tile-v">{formatEUR(kpis.periodExpense)}</div>
          <div className="tile-d">ce mois-ci</div>
        </div>
      </div>

      {advice.length > 0 && (
        <section aria-label="Conseils et alertes" className="stack" style={{ gap: '0.5rem' }}>
          {advice.slice(0, 3).map((a) => {
            const Icon = SEVERITY_ICON[a.severity]
            return (
              <p key={a.id} className={`notice notice-${a.severity}`} style={{ margin: 0 }}>
                <Icon />
                <span>{a.text}</span>
              </p>
            )
          })}
        </section>
      )}

      <section className="card" aria-label="Solde de chaque compte">
        <span className="label" style={{ marginBottom: '0.3rem' }}>
          Mes comptes
        </span>
        <ul className="rows">
          {accounts.map((a) => {
            const balance = accountBalance(a, data.transactions)
            // Un solde négatif se voit : rouge, ou simple mention du découvert restant.
            const inOverdraft = balance < 0
            return (
              <li key={a.id} className="row" style={{ minHeight: 44 }}>
                <span className="glyph glyph-sm" aria-hidden="true">
                  {a.icon}
                </span>
                <span className="row-main">
                  <span className="row-title">{a.name}</span>
                  {inOverdraft && a.overdraft > 0 && (
                    <span className="row-meta">
                      découvert utilisé · reste {formatEURCompact(Math.max(0, a.overdraft + balance))}
                    </span>
                  )}
                </span>
                <span
                  className="amount"
                  style={inOverdraft ? { color: 'var(--critical)' } : undefined}
                >
                  {formatEUR(balance)}
                </span>
              </li>
            )
          })}
        </ul>
      </section>

      {commitments.activeCount > 0 && (
        <Link
          to="/prelevements"
          className="card"
          style={{ textDecoration: 'none', color: 'inherit', display: 'block' }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
            <span className="glyph" aria-hidden="true">
              <IconCard />
            </span>
            <span className="row-main">
              <span className="row-title">Prélèvements du mois</span>
              <span className="row-meta">
                {commitments.activeCount} abonnement{commitments.activeCount > 1 ? 's' : ''} ou prêts
                · dont {formatEURCompact(commitments.essentialMonthly)} indispensables
              </span>
            </span>
            <span className="amount">{formatEUR(commitments.totalMonthly)}</span>
            <IconChevronRight className="chev" />
          </div>
        </Link>
      )}

      <div className="cols-2">
        <section className="card">
          <h2>Répartition des dépenses</h2>
          <CategoryDonut slices={slices} mode={mode} />
        </section>

        <section className="card">
          <h2>Évolution sur 6 mois</h2>
          <MonthlyBars series={series} mode={mode} />
        </section>
      </div>

      <section className="card">
        <h2>Budget vs réel</h2>
        <BudgetVsActual statuses={statuses} mode={mode} />
      </section>
    </>
  )
}
