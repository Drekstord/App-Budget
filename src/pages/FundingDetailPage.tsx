import { useNavigate, useParams } from 'react-router-dom'
import { useStore } from '../store/useStore.ts'
import { useResolvedTheme } from '../theme.ts'
import { formatEUR } from '../domain/money.ts'
import { todayISO } from '../domain/periods.ts'
import { computeFundingPlan, type Feasibility } from '../domain/funding.ts'
import { FundingChart } from '../components/charts.tsx'

const SEVERITY_ICONS = { critical: '⛔', warning: '⚠️', info: '💡', good: '✅' } as const

const FEASIBILITY_LABEL: Record<Feasibility, string> = {
  covered_now: 'Finançable maintenant',
  feasible: 'Atteignable',
  feasible_variable: 'Atteignable sous conditions',
  infeasible: 'Hors de portée',
}

const longDate = new Intl.DateTimeFormat('fr-FR', { day: 'numeric', month: 'long', year: 'numeric' })

export function FundingDetailPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const data = useStore((s) => s.data)
  const deleteFundingPlan = useStore((s) => s.deleteFundingPlan)
  const mode = useResolvedTheme()

  if (!data) return null
  const plan = data.fundingPlans.find((p) => p.id === id && !p.deletedAt)
  if (!plan) {
    return (
      <div className="empty-state">
        <p>Ce plan n’existe plus.</p>
        <button type="button" className="btn" onClick={() => navigate('/plans')}>
          Retour aux plans
        </button>
      </div>
    )
  }

  const result = computeFundingPlan(plan, data, todayISO())
  const allocated = result.draws.filter((d) => d.allocated > 0)

  const remove = async () => {
    if (!window.confirm(`Supprimer le plan « ${plan.name} » ?`)) return
    await deleteFundingPlan(plan.id)
    navigate('/plans')
  }

  return (
    <div className="stack">
      <div>
        <button type="button" className="btn btn-ghost" onClick={() => navigate('/plans')}>
          ← Tous les plans
        </button>
      </div>

      <section className="card">
        <h2 style={{ marginBottom: '0.25rem' }}>{plan.targetLabel}</h2>
        <p className="chart-note" style={{ marginTop: 0 }}>
          {formatEUR(plan.targetAmount)} · prévu le {longDate.format(new Date(plan.targetDate + 'T00:00:00'))}
          {result.monthsRemaining > 0 && ` · dans ${result.monthsRemaining} mois`}
        </p>
        <p
          className={`notice notice-${result.feasibility === 'infeasible' ? 'critical' : result.feasibility === 'feasible_variable' ? 'warning' : 'good'}`}
          style={{ margin: 0, fontWeight: 600 }}
        >
          {FEASIBILITY_LABEL[result.feasibility]}
        </p>
      </section>

      <div className="grid-2 grid-4">
        <div className="kpi">
          <div className="kpi-label">Mobilisable maintenant</div>
          <div className="kpi-value">{formatEUR(result.drawableNow)}</div>
          <div className="kpi-sub">selon tes priorités</div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Reste à réunir</div>
          <div className="kpi-value">{formatEUR(result.shortfallNow)}</div>
          <div className="kpi-sub">au-delà du mobilisable</div>
        </div>
        <div className="kpi">
          <div className="kpi-label">À épargner par mois</div>
          <div className="kpi-value">
            {result.monthsRemaining > 0 ? formatEUR(result.requiredMonthlySaving) : '—'}
          </div>
          <div className="kpi-sub">
            {result.averageMonthlyNet > 0
              ? `marge ~${formatEUR(result.averageMonthlyNet)}/mois`
              : 'd’ici l’échéance'}
          </div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Projeté à l’échéance</div>
          <div className="kpi-value">{formatEUR(result.projectedAtTarget)}</div>
          <div className="kpi-sub">revenus fixes seuls</div>
        </div>
      </div>

      {result.warnings.length > 0 && (
        <section aria-label="Analyse du plan" className="stack" style={{ gap: '0.5rem' }}>
          {result.warnings.map((w) => (
            <p key={w.id} className={`notice notice-${w.severity}`} style={{ margin: 0 }}>
              <span aria-hidden="true">{SEVERITY_ICONS[w.severity]}</span>
              <span>{w.text}</span>
            </p>
          ))}
        </section>
      )}

      <section className="card chart-block">
        <h2>Trajectoire jusqu’à l’échéance</h2>
        <p className="chart-note">
          Évolution de ce que tu peux mobiliser, mois par mois, comparée à l’objectif.
        </p>
        <FundingChart result={result} mode={mode} />
      </section>

      <section className="card">
        <h2>Comment financer dès maintenant</h2>
        {allocated.length === 0 ? (
          <p className="chart-note" style={{ margin: 0 }}>
            Aucun compte n’est mobilisable pour l’instant (soldes insuffisants ou protégés). Le plan
            repose sur l’épargne à constituer d’ici l’échéance.
          </p>
        ) : (
          <>
            <p className="chart-note">
              Répartition suggérée, en respectant l’ordre de priorité et tes protections :
            </p>
            <ul className="list">
              {allocated.map((d) => (
                <li key={d.accountId} className="list-item">
                  <span className="item-icon" aria-hidden="true">
                    {d.icon}
                  </span>
                  <span className="item-body">
                    <span className="item-title">{d.name}</span>
                    <br />
                    <span className="item-sub">
                      solde {formatEUR(d.balance)}
                      {d.keepMin > 0 && ` · préserver ${formatEUR(d.keepMin)}`}
                    </span>
                  </span>
                  <span className="amount">{formatEUR(d.allocated)}</span>
                </li>
              ))}
            </ul>
          </>
        )}
        {result.draws.some((d) => d.excluded || (d.keepMin > 0 && d.allocated < d.balance)) && (
          <p className="chart-note" style={{ marginBottom: 0 }}>
            Comptes protégés :{' '}
            {result.draws
              .filter((d) => d.excluded)
              .map((d) => `${d.name} (intact)`)
              .concat(
                result.draws
                  .filter((d) => !d.excluded && d.keepMin > 0)
                  .map((d) => `${d.name} (≥ ${formatEUR(d.keepMin)})`),
              )
              .join(', ') || '—'}
          </p>
        )}
      </section>

      <div style={{ display: 'flex', gap: '0.5rem' }}>
        <button type="button" className="btn btn-danger" onClick={() => void remove()}>
          Supprimer
        </button>
        <button
          type="button"
          className="btn btn-primary"
          style={{ flex: 1 }}
          onClick={() => navigate(`/plans/${plan.id}/modifier`)}
        >
          Modifier le plan
        </button>
      </div>
    </div>
  )
}
