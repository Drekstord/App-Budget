import { useState } from 'react'
import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { useStore } from '../store/useStore.ts'
import { periodForDate, todayISO } from '../domain/periods.ts'
import { Toasts } from './Toasts.tsx'
import { TransactionForm } from './TransactionForm.tsx'
import { IconHome, IconList, IconLock, IconMore, IconPlus, IconTarget } from './icons.tsx'

// Cinq destinations seulement : Comptes, Prélèvements, Catégories et Réglages
// vivent sous « Plus ». Le bouton d'ajout occupe le centre de la barre, là où le
// pouce tombe, et ne recouvre plus la fin des listes.
const NAV_ITEMS = [
  { to: '/', label: 'Accueil', Icon: IconHome },
  { to: '/transactions', label: 'Opérations', Icon: IconList },
  { to: '/budgets', label: 'Budgets', Icon: IconTarget },
  { to: '/plus', label: 'Plus', Icon: IconMore },
]

const TITLES: Record<string, string> = {
  '/': 'Accueil',
  '/transactions': 'Opérations',
  '/budgets': 'Budgets',
  '/prelevements': 'Prélèvements',
  '/comptes': 'Comptes',
  '/categories': 'Catégories',
  '/reglages': 'Réglages',
  '/plus': 'Plus',
}

function titleFor(pathname: string): string {
  if (pathname === '/plans') return 'Projets'
  if (pathname === '/plans/nouveau') return 'Nouveau projet'
  if (pathname.startsWith('/plans/') && pathname.endsWith('/modifier')) return 'Modifier le projet'
  if (pathname.startsWith('/plans/')) return 'Projet'
  return TITLES[pathname] ?? 'App Budget'
}

export function Layout() {
  const lock = useStore((s) => s.lock)
  const monthStartDay = useStore((s) => s.data?.settings.monthStartDay ?? 1)
  const { pathname } = useLocation()
  const [addOpen, setAddOpen] = useState(false)
  const period = periodForDate(todayISO(), monthStartDay)

  return (
    <div className="app-shell">
      <Toasts />

      <nav className="app-nav" aria-label="Navigation principale">
        <span className="nav-center">
          <button
            type="button"
            className="nav-fab"
            onClick={() => setAddOpen(true)}
            aria-label="Ajouter une opération"
          >
            <IconPlus />
            <span className="nav-fab-label">Ajouter</span>
          </button>
        </span>
        {NAV_ITEMS.map(({ to, label, Icon }) => (
          <NavLink key={to} to={to} end={to === '/'}>
            <Icon size={21} />
            <span>{label}</span>
          </NavLink>
        ))}
      </nav>

      <div className="app-content">
        <header className="app-header">
          <div>
            <span className="period">{period.label}</span>
            <h1>{titleFor(pathname)}</h1>
          </div>
          <button
            type="button"
            className="icon-btn"
            onClick={lock}
            aria-label="Verrouiller l’application"
            title="Verrouiller"
          >
            <IconLock />
          </button>
        </header>

        <main className="app-main">
          <Outlet />
        </main>
      </div>

      {/* Saisie accessible depuis n'importe quel écran via le bouton central. */}
      <TransactionForm open={addOpen} onClose={() => setAddOpen(false)} />
    </div>
  )
}
