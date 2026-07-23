import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { useStore } from '../store/useStore.ts'
import { Toasts } from './Toasts.tsx'

const NAV_ITEMS = [
  { to: '/', label: 'Accueil', icon: '📊' },
  { to: '/transactions', label: 'Opérations', icon: '🧾' },
  { to: '/budgets', label: 'Budgets', icon: '🎯' },
  { to: '/plans', label: 'Projets', icon: '🧮' },
  { to: '/comptes', label: 'Comptes', icon: '🏦' },
  { to: '/reglages', label: 'Réglages', icon: '⚙️' },
]

const TITLES: Record<string, string> = {
  '/': 'Tableau de bord',
  '/transactions': 'Opérations',
  '/budgets': 'Budgets',
  '/comptes': 'Comptes',
  '/categories': 'Catégories',
  '/reglages': 'Réglages',
}

function titleFor(pathname: string): string {
  if (pathname === '/plans') return 'Plans de financement'
  if (pathname === '/plans/nouveau') return 'Nouveau plan'
  if (pathname.startsWith('/plans/') && pathname.endsWith('/modifier')) return 'Modifier le plan'
  if (pathname.startsWith('/plans/')) return 'Plan de financement'
  return TITLES[pathname] ?? 'App Budget'
}

export function Layout() {
  const lock = useStore((s) => s.lock)
  const { pathname } = useLocation()

  return (
    <div className="app-shell">
      <Toasts />
      <nav className="app-nav" aria-label="Navigation principale">
        {NAV_ITEMS.map((item) => (
          <NavLink key={item.to} to={item.to} end={item.to === '/'}>
            <span className="nav-icon" aria-hidden="true">
              {item.icon}
            </span>
            <span>{item.label}</span>
          </NavLink>
        ))}
      </nav>
      <div className="app-content">
        <header className="app-header">
          <h1>{titleFor(pathname)}</h1>
          <button
            type="button"
            className="icon-btn"
            onClick={lock}
            aria-label="Verrouiller l’application"
            title="Verrouiller"
          >
            🔒
          </button>
        </header>
        <main className="app-main">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
