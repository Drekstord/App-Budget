import { useNavigate, useParams } from 'react-router-dom'
import { useStore } from '../store/useStore.ts'
import { useResolvedTheme } from '../theme.ts'
import { formatEUR } from '../domain/money.ts'
import { todayISO } from '../domain/periods.ts'
import { computeFundingPlans, type Feasibility } from '../domain/funding.ts'
import { FundingChart } from '../components/charts.tsx'
import { IconAlert, IconCheckCircle, IconInfo } from '../components/icons.tsx'

const SEVERITY_ICON = {
  critical: IconAlert,
  warning: IconAlert,
  info: IconInfo,
  good: IconCheckCircle,
} as const

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
  // Calcul conscient des autres projets (réservations par échéance).
  const entry = computeFundingPlans(data, todayISO()).find((p) => p.plan.id === id)
  if (!entry) {
    return (
      <div className="empty-state">
        <p>Ce plan n’existe plus.</p>
        <button type="button" className="btn" onClick={() => navigate('/plans')}>
          Retour aux plans
        </button>
      </div>
    )
  }
  const { plan, result } = entry
  const allocated = result.draws.filter((d) => d.allocated > 0)

  const remove = async () => {
    if (!window.confirm(`Supprimer le plan « ${plan.name} » ?`)) return
    await deleteFundingPlan(plan.id)
    navigate('/plans')
  }

  const feasibilityTone =
    result.feasibility === 'infeasible'
      ? 'critical'
      : result.feasibility === 'feasible_variable'
        ? 'warning'
        : 'good'

  return (
    <>
      <div>
        <button type="button" className="btn btn-ghost" onClick={() => navigate('/plans')}>
          ← Tous les projets
        </button>
      </div>

      <section className="card" aria-label="Objectif du projet">
        <div className="plan-head">
          <span className="row-main">
            <span className="row-title plan-title">{plan.targetLabel}</span>
            <span className="row-meta">
              prévu le {longDate.format(new Date(plan.targetDate + 'T00:00:00'))}
              {result.monthsRemaining > 0 && ` · dans ${result.monthsRemaining} mois`}
            </span>
          </span>
          <span className={`pill pill-${feasibilityTone === 'critical' ? 'over' : feasibilityTone} plan-badge`}>
            {FEASIBILITY_LABEL[result.feasibility]}
          </span>
        </div>
        <p className="hero hero-sm">{formatEUR(plan.targetAmount)}</p>
        <div
          className={`meter ${result.feasibility === 'infeasible' ? 'meter-over' : ''}`}
          role="img"
          aria-label={`${formatEUR(result.drawableNow)} mobilisables sur ${formatEUR(plan.targetAmount)}`}
        >
          <span
            style={{
              width: `${
                plan.targetAmount > 0
                  ? Math.max(0, Math.min(100, (result.drawableNow / plan.targetAmount) * 100))
                  : 0
              }%`,
            }}
          />
        </div>
      </section>

      <div className="tiles">
        <div className="tile">
          <span className="label">Mobilisable</span>
          <div className="tile-v">{formatEUR(result.drawableNow)}</div>
          <div className="tile-d">selon tes priorités</div>
        </div>
        <div className="tile">
          <span className="label">Reste à réunir</span>
          <div className="tile-v">{formatEUR(result.shortfallNow)}</div>
          <div className="tile-d">au-delà du mobilisable</div>
        </div>
        <div className="tile">
          <span className="label">À épargner / mois</span>
          <div className="tile-v">
            {result.monthsRemaining > 0 ? formatEUR(result.requiredMonthlySaving) : '—'}
          </div>
          <div className="tile-d">
            {result.averageMonthlyNet > 0
              ? `marge ~${formatEUR(result.averageMonthlyNet)}/mois`
              : 'd’ici l’échéance'}
          </div>
        </div>
        <div className="tile">
          <span className="label">Projeté</span>
          <div className="tile-v">{formatEUR(result.projectedAtTarget)}</div>
          <div className="tile-d">revenus fixes seuls</div>
        </div>
      </div>

      {result.reservedByOtherPlans > 0 && (
        <p className="notice notice-info" style={{ margin: 0 }}>
          <IconInfo />
          <span>
            {formatEUR(result.reservedByOtherPlans)} de ta trésorerie sont déjà réservés par un
            projet à l’échéance plus proche
            {result.aheadPlanNames.length > 0 && ` (${result.aheadPlanNames.join(', ')})`}. Ce plan
            ne compte que sur ce qui reste.
          </span>
        </p>
      )}

      {result.warnings.length > 0 && (
        <section aria-label="Analyse du plan" className="stack" style={{ gap: '0.5rem' }}>
          {result.warnings.map((w) => {
            const Icon = SEVERITY_ICON[w.severity]
            return (
              <p key={w.id} className={`notice notice-${w.severity}`} style={{ margin: 0 }}>
                <Icon />
                <span>{w.text}</span>
              </p>
            )
          })}
        </section>
      )}

      <section className="card">
        <span className="label" style={{ marginBottom: '0.5rem' }}>
          Marche à suivre
        </span>
        <ol className="steps">
          {result.coveredNow > 0 && (
            <li>
              <strong>Mobilise maintenant {formatEUR(result.coveredNow)}</strong> depuis tes comptes
              (répartition détaillée plus bas).
            </li>
          )}
          {result.shortfallNow > 0 && result.monthsRemaining > 0 && (
            <li>
              <strong>Mets de côté {formatEUR(result.requiredMonthlySaving)}/mois</strong>
              {(() => {
                const days = Math.max(
                  1,
                  Math.round(
                    (new Date(plan.targetDate + 'T00:00:00').getTime() - Date.now()) / 86_400_000,
                  ),
                )
                return <> (≈ {formatEUR(Math.ceil(result.shortfallNow / days))}/jour)</>
              })()}{' '}
              jusqu’à l’échéance.
            </li>
          )}
          {result.feasibility === 'covered_now' && (
            <li>Tu peux financer cette dépense <strong>intégralement dès maintenant</strong>.</li>
          )}
          {result.feasibility === 'feasible_variable' && (
            <li>Compte sur tes revenus variables pour boucler — sinon, épargne un peu plus.</li>
          )}
          {result.feasibility === 'infeasible' && (
            <li>
              Objectif hors de portée en l’état : <strong>repousse l’échéance</strong>, réduis le
              montant, ou ajoute des revenus.
            </li>
          )}
        </ol>
      </section>

      <section className="card">
        <h2>Trajectoire jusqu’à l’échéance</h2>
        <p className="chart-note">
          Évolution de ce que tu peux mobiliser, mois par mois, comparée à l’objectif.
        </p>
        <FundingChart result={result} mode={mode} />
      </section>

      <section className="card">
        <span className="label" style={{ marginBottom: '0.3rem' }}>
          Comment financer dès maintenant
        </span>
        {allocated.length === 0 ? (
          <p className="hint" style={{ marginTop: 0 }}>
            Aucun compte n’est mobilisable pour l’instant (soldes insuffisants ou protégés). Le plan
            repose sur l’épargne à constituer d’ici l’échéance.
          </p>
        ) : (
          <>
            <p className="hint" style={{ marginTop: 0, marginBottom: '0.3rem' }}>
              Répartition suggérée, en respectant l’ordre de priorité et tes protections :
            </p>
            <ul className="rows">
              {allocated.map((d) => (
                <li key={d.accountId} className="row">
                  <span className="glyph glyph-sm" aria-hidden="true">
                    {d.icon}
                  </span>
                  <span className="row-main">
                    <span className="row-title">{d.name}</span>
                    <span className="row-meta">
                      solde {formatEUR(d.balance)}
                      {d.reservedByOthers > 0 && ` · ${formatEUR(d.reservedByOthers)} réservés ailleurs`}
                      {d.keepMin > 0 && ` · préserver ${formatEUR(d.keepMin)}`}
                      {d.fromOverdraft > 0 && ` · dont ${formatEUR(d.fromOverdraft)} en découvert`}
                    </span>
                  </span>
                  <span className="amount">{formatEUR(d.allocated)}</span>
                </li>
              ))}
            </ul>
          </>
        )}
        {result.draws.some((d) => d.excluded || (d.keepMin > 0 && d.allocated < d.balance)) && (
          <p className="hint">
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

      <div className="sheet-actions">
        <button type="button" className="btn btn-danger" onClick={() => void remove()}>
          Supprimer
        </button>
        <button
          type="button"
          className="btn btn-primary"
          style={{ flex: 1 }}
          onClick={() => navigate(`/plans/${plan.id}/modifier`)}
        >
          Modifier le projet
        </button>
      </div>
    </>
  )
}
