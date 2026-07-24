import { useNavigate } from 'react-router-dom'
import { useStore } from '../store/useStore.ts'
import { formatEUR } from '../domain/money.ts'
import { todayISO } from '../domain/periods.ts'
import { computeFundingPlans, fundingActionPlan, type Feasibility } from '../domain/funding.ts'

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
  // Plans conscients les uns des autres, dans l'ordre des échéances.
  const plans = computeFundingPlans(data, todayISO())
  const totalReserved = plans.reduce((sum, p) => sum + p.result.coveredNow, 0)
  const action = fundingActionPlan(data, todayISO())

  return (
    <div className="stack">
      <p className="chart-note">
        Prépare une grosse dépense à venir : déclare tes comptes et leurs priorités, tes revenus et
        les dépenses prévues, et obtiens un plan pour y faire face.
      </p>

      {plans.length > 1 && (
        <p className="notice notice-info" style={{ margin: 0 }}>
          <span aria-hidden="true">🔗</span>
          <span>
            Tes {plans.length} projets partagent la même trésorerie. Les plus urgents (échéance la
            plus proche) se servent en premier ; les suivants tiennent compte de ce qui reste.
            {totalReserved > 0 && ` Déjà engagé au total : ${formatEUR(totalReserved)}.`}
          </span>
        </p>
      )}

      {action.steps.length > 0 && (
        <section className="card">
          <h2>Marche à suivre</h2>
          <p className="chart-note">
            Ordre conseillé (échéance la plus proche d’abord)
            {action.totalMonthlySaving > 0 && (
              <> · à mettre de côté au total : <strong>{formatEUR(action.totalMonthlySaving)}/mois</strong></>
            )}
            .
          </p>
          <ol className="steps">
            {action.steps.map((step) => (
              <li key={step.planId}>
                <strong>{step.label}</strong>
                {step.mobilizeNow > 0 && <> — mobilise {formatEUR(step.mobilizeNow)} maintenant</>}
                {step.monthlySaving > 0 ? (
                  <>
                    {step.mobilizeNow > 0 ? ', puis ' : ' — '}
                    {formatEUR(step.monthlySaving)}/mois
                    {step.dailySaving > 0 && <> (≈ {formatEUR(step.dailySaving)}/jour)</>}
                  </>
                ) : step.feasibility === 'infeasible' ? (
                  <> — hors de portée en l’état</>
                ) : (
                  <> — déjà couvert</>
                )}
              </li>
            ))}
          </ol>
        </section>
      )}

      {plans.length === 0 ? (
        <div className="empty-state">
          <p>Aucun plan pour l’instant.</p>
          <p>Crée ton premier plan de financement avec le bouton +.</p>
        </div>
      ) : (
        plans.map(({ plan, result }) => {
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
                {result.reservedByOtherPlans > 0 && (
                  <>
                    <br />
                    <span style={{ color: 'var(--muted)' }}>
                      après {formatEUR(result.reservedByOtherPlans)} réservés par un projet plus
                      urgent
                    </span>
                  </>
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
