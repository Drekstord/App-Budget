import { useNavigate } from 'react-router-dom'
import { useStore } from '../store/useStore.ts'
import { formatEUR } from '../domain/money.ts'
import { todayISO } from '../domain/periods.ts'
import { computeFundingPlans, fundingActionPlan, type Feasibility } from '../domain/funding.ts'
import { IconInfo, IconPlus } from '../components/icons.tsx'

const BADGE: Record<Feasibility, { label: string; cls: string }> = {
  covered_now: { label: 'Finançable', cls: 'pill-good' },
  feasible: { label: 'Atteignable', cls: 'pill-good' },
  feasible_variable: { label: 'Sous conditions', cls: 'pill-warning' },
  infeasible: { label: 'Hors de portée', cls: 'pill-over' },
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
    <>
      <p className="hint" style={{ marginTop: 0 }}>
        Prépare une grosse dépense à venir : déclare tes comptes et leurs priorités, tes revenus et
        les dépenses prévues, et obtiens un plan pour y faire face.
      </p>

      {plans.length > 1 && (
        <p className="notice notice-info" style={{ margin: 0 }}>
          <IconInfo />
          <span>
            Tes {plans.length} projets partagent la même trésorerie. Les plus urgents (échéance la
            plus proche) se servent en premier ; les suivants tiennent compte de ce qui reste.
            {totalReserved > 0 && ` Déjà engagé au total : ${formatEUR(totalReserved)}.`}
          </span>
        </p>
      )}

      {action.steps.length > 0 && (
        <section className="card">
          <span className="label" style={{ marginBottom: '0.3rem' }}>
            Marche à suivre
          </span>
          <p className="hint" style={{ marginTop: 0, marginBottom: '0.7rem' }}>
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
          <p>Aucun projet pour l’instant.</p>
          <p>Crée ton premier plan de financement ci-dessous.</p>
        </div>
      ) : (
        plans.map(({ plan, result }) => {
          const badge = BADGE[result.feasibility]
          const progress =
            plan.targetAmount > 0
              ? Math.max(0, Math.min(100, (result.drawableNow / plan.targetAmount) * 100))
              : 0
          return (
            <button
              key={plan.id}
              type="button"
              className="card plan-card"
              onClick={() => navigate(`/plans/${plan.id}`)}
            >
              <div className="plan-head">
                <span className="row-main">
                  <span className="row-title plan-title">{plan.targetLabel}</span>
                  <span className="row-meta">
                    {formatEUR(plan.targetAmount)} · échéance{' '}
                    {shortDate.format(new Date(plan.targetDate + 'T00:00:00'))}
                  </span>
                </span>
                <span className={`pill ${badge.cls} plan-badge`}>{badge.label}</span>
              </div>

              <div
                className={`meter ${result.feasibility === 'infeasible' ? 'meter-over' : ''}`}
                role="img"
                aria-label={`${Math.round(progress)} % du besoin déjà mobilisable`}
              >
                <span style={{ width: `${progress}%` }} />
              </div>

              <p className="hint">
                Mobilisable maintenant : <strong>{formatEUR(result.drawableNow)}</strong>
                {result.shortfallNow > 0 && result.monthsRemaining > 0 && (
                  <> · à épargner {formatEUR(result.requiredMonthlySaving)}/mois</>
                )}
                {result.reservedByOtherPlans > 0 && (
                  <>
                    <br />
                    après {formatEUR(result.reservedByOtherPlans)} réservés par un projet plus urgent
                  </>
                )}
              </p>
            </button>
          )
        })
      )}

      <button
        type="button"
        className="btn btn-primary"
        onClick={() => navigate('/plans/nouveau')}
      >
        <IconPlus size={18} /> Créer un projet
      </button>
    </>
  )
}
