import { useNavigate } from 'react-router-dom'
import { useStore } from '../store/useStore.ts'
import { alive } from '../domain/types.ts'
import { formatEUR } from '../domain/money.ts'
import { todayISO } from '../domain/periods.ts'
import { computeFundingPlan, type Feasibility } from '../domain/funding.ts'

const BADGE: Record<Feasibility, { label: string; cls: string }> = {
  covered_now: { label: 'Finançable maintenant', cls: 'notice-good' },
  feasible: { label: 'Atteignable', cls: 'notice-good' },
  feasible_variable: { label: 'Sous conditions', cls: 'notice-warning' },
  infeasible: { label: 'Hors de portée', cls: 'notice-critical' },
}

const shortDate = new Intl.DateTimeFormat('fr-FR', { month: 'short', year: 'numeric' })

export function FundingPage() {
  const data = useStore((s) => s.data)
  const navigate = useNavigate()
  if (!data) return null
  const plans = alive(data.fundingPlans).sort((a, b) => a.targetDate.localeCompare(b.targetDate))

  return (
    <div className="stack">
      <p className="chart-note">
        Prépare une grosse dépense à venir : déclare tes comptes et leurs priorités, tes revenus et
        les dépenses prévues, et obtiens un plan pour y faire face.
      </p>

      {plans.length === 0 ? (
        <div className="empty-state">
          <p>Aucun plan pour l’instant.</p>
          <p>Crée ton premier plan de financement avec le bouton +.</p>
        </div>
      ) : (
        plans.map((plan) => {
          const result = computeFundingPlan(plan, data, todayISO())
          const badge = BADGE[result.feasibility]
          return (
            <button
              key={plan.id}
              type="button"
              className="card plan-card"
              onClick={() => navigate(`/plans/${plan.id}`)}
            >
              <div style={{ display: 'flex', justifyContent: 'space-between', gap: '0.5rem' }}>
                <div style={{ minWidth: 0 }}>
                  <div className="item-title" style={{ fontSize: '1.05rem' }}>
                    {plan.targetLabel}
                  </div>
                  <div className="item-sub">
                    {formatEUR(plan.targetAmount)} · échéance{' '}
                    {shortDate.format(new Date(plan.targetDate + 'T00:00:00'))}
                  </div>
                </div>
                <span className={`notice ${badge.cls} plan-badge`}>{badge.label}</span>
              </div>
              <div className="item-sub" style={{ marginTop: '0.5rem' }}>
                Mobilisable maintenant : <strong>{formatEUR(result.drawableNow)}</strong>
                {result.shortfallNow > 0 && result.monthsRemaining > 0 && (
                  <> · à épargner : {formatEUR(result.requiredMonthlySaving)}/mois</>
                )}
              </div>
            </button>
          )
        })
      )}

      <button
        type="button"
        className="fab"
        aria-label="Créer un plan de financement"
        onClick={() => navigate('/plans/nouveau')}
      >
        +
      </button>
    </div>
  )
}
