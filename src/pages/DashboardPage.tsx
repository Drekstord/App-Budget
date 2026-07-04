import { useState } from 'react'
import { useStore } from '../store/useStore.ts'
import { useResolvedTheme } from '../theme.ts'
import { formatEUR } from '../domain/money.ts'
import { periodForDate, todayISO } from '../domain/periods.ts'
import {
  budgetStatuses,
  computeKpis,
  expensesByRootCategory,
  periodSeries,
} from '../domain/stats.ts'
import { computeAdvice, type AdviceSeverity } from '../domain/advice.ts'
import { BudgetVsActual, CategoryDonut, MonthlyBars } from '../components/charts.tsx'
import { TransactionForm } from '../components/TransactionForm.tsx'

const SEVERITY_ICONS: Record<AdviceSeverity, string> = {
  critical: '⛔',
  warning: '⚠️',
  info: '💡',
  good: '✅',
}

export function DashboardPage() {
  const data = useStore((s) => s.data)
  const mode = useResolvedTheme()
  const [formOpen, setFormOpen] = useState(false)

  if (!data) return null
  const period = periodForDate(todayISO(), data.settings.monthStartDay)
  const kpis = computeKpis(data, period)
  const slices = expensesByRootCategory(data, period)
  const series = periodSeries(data, 6)
  const statuses = budgetStatuses(data, period)
  const advice = computeAdvice(data)

  return (
    <div className="stack">
      <p className="chart-note">Période : {period.label}</p>

      <div className="grid-2 grid-4">
        <div className="kpi">
          <div className="kpi-label">Solde total</div>
          <div className="kpi-value">{formatEUR(kpis.totalBalance)}</div>
          <div className="kpi-sub">tous comptes</div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Dépenses</div>
          <div className="kpi-value">{formatEUR(kpis.periodExpense)}</div>
          <div className="kpi-sub">ce mois-ci</div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Reste à dépenser</div>
          <div className="kpi-value">{formatEUR(kpis.remainingBudget)}</div>
          <div className="kpi-sub">sur les budgets définis</div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Taux d’épargne</div>
          <div className="kpi-value">
            {kpis.savingsRate === null ? '—' : `${kpis.savingsRate} %`}
          </div>
          <div className="kpi-sub">des revenus du mois</div>
        </div>
      </div>

      {advice.length > 0 && (
        <section aria-label="Conseils et alertes" className="stack" style={{ gap: '0.5rem' }}>
          {advice.slice(0, 4).map((a) => (
            <p key={a.id} className={`notice notice-${a.severity}`} style={{ margin: 0 }}>
              <span aria-hidden="true">{SEVERITY_ICONS[a.severity]}</span>
              <span>{a.text}</span>
            </p>
          ))}
        </section>
      )}

      <div className="grid-2-desktop stack">
        <section className="card chart-block">
          <h2>Répartition des dépenses</h2>
          <CategoryDonut slices={slices} mode={mode} />
        </section>

        <section className="card chart-block">
          <h2>Évolution sur 6 mois</h2>
          <MonthlyBars series={series} mode={mode} />
        </section>
      </div>

      <section className="card chart-block">
        <h2>Budget vs réel</h2>
        <BudgetVsActual statuses={statuses} mode={mode} />
      </section>

      <button
        type="button"
        className="fab"
        aria-label="Ajouter une opération"
        onClick={() => setFormOpen(true)}
      >
        +
      </button>
      <TransactionForm open={formOpen} onClose={() => setFormOpen(false)} />
    </div>
  )
}
