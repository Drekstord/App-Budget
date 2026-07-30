import { Link } from 'react-router-dom'
import { useStore } from '../store/useStore.ts'
import { alive } from '../domain/types.ts'
import { formatEUR } from '../domain/money.ts'
import { todayISO } from '../domain/periods.ts'
import { totalBalance } from '../domain/stats.ts'
import { computeCommitmentSummary } from '../domain/subscriptions.ts'
import {
  IconCard,
  IconChevronRight,
  IconSettings,
  IconTag,
  IconTarget,
  IconWallet,
} from '../components/icons.tsx'

/** Regroupe les écrans qui ne méritent pas un onglet permanent. */
export function MorePage() {
  const data = useStore((s) => s.data)
  if (!data) return null

  const commitments = computeCommitmentSummary(data, todayISO())
  const accountCount = alive(data.accounts).filter((a) => !a.archived).length
  const planCount = alive(data.fundingPlans).length
  const categoryCount = alive(data.categories).length

  const entries = [
    {
      to: '/plans',
      Icon: IconTarget,
      title: 'Projets',
      sub:
        planCount > 0
          ? `${planCount} projet${planCount > 1 ? 's' : ''} de financement`
          : 'Préparer une grosse dépense',
    },
    {
      to: '/prelevements',
      Icon: IconCard,
      title: 'Abonnements & prêts',
      sub:
        commitments.activeCount > 0
          ? `${formatEUR(commitments.totalMonthly)} par mois`
          : 'Aucun prélèvement enregistré',
    },
    {
      to: '/comptes',
      Icon: IconWallet,
      title: 'Comptes',
      sub: `${accountCount} compte${accountCount > 1 ? 's' : ''} · ${formatEUR(
        totalBalance(data.accounts, data.transactions),
      )}`,
    },
    {
      to: '/categories',
      Icon: IconTag,
      title: 'Catégories',
      sub: `${categoryCount} catégories`,
    },
    {
      to: '/reglages',
      Icon: IconSettings,
      title: 'Réglages',
      sub: 'PIN, thème, sauvegarde, alertes',
    },
  ]

  return (
    <div className="card">
      <nav className="hub" aria-label="Autres écrans">
        {entries.map(({ to, Icon, title, sub }) => (
          <Link key={to} to={to}>
            <span className="hub-icon">
              <Icon />
            </span>
            <span className="hub-main">
              <span className="row-title">{title}</span>
              <span className="hub-sub">{sub}</span>
            </span>
            <IconChevronRight className="chev" />
          </Link>
        ))}
      </nav>
    </div>
  )
}
