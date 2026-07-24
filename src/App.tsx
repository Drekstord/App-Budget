import { useEffect } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { useStore } from './store/useStore.ts'
import { useResolvedTheme } from './theme.ts'
import { Layout } from './components/Layout.tsx'
import { LockPage } from './pages/LockPage.tsx'
import { DashboardPage } from './pages/DashboardPage.tsx'
import { TransactionsPage } from './pages/TransactionsPage.tsx'
import { BudgetsPage } from './pages/BudgetsPage.tsx'
import { FundingPage } from './pages/FundingPage.tsx'
import { FundingEditorPage } from './pages/FundingEditorPage.tsx'
import { FundingDetailPage } from './pages/FundingDetailPage.tsx'
import { AccountsPage } from './pages/AccountsPage.tsx'
import { SubscriptionsPage } from './pages/SubscriptionsPage.tsx'
import { CategoriesPage } from './pages/CategoriesPage.tsx'
import { SettingsPage } from './pages/SettingsPage.tsx'

/** Verrouillage automatique après inactivité ou mise en arrière-plan. */
function useAutoLock() {
  const phase = useStore((s) => s.phase)
  const delayMinutes = useStore((s) => s.data?.settings.lockDelayMinutes ?? 5)
  const lock = useStore((s) => s.lock)
  const extendSession = useStore((s) => s.extendSession)

  useEffect(() => {
    if (phase !== 'unlocked' || delayMinutes <= 0) return
    const delayMs = delayMinutes * 60_000
    let timer = window.setTimeout(lock, delayMs)
    let hiddenAt: number | null = null
    let lastExtend = Date.now()

    const reset = () => {
      window.clearTimeout(timer)
      timer = window.setTimeout(lock, delayMs)
      // Prolonge aussi la session persistée (au plus une fois toutes les 45 s)
      // pour qu'un rechargement ne redemande pas le PIN pendant l'utilisation.
      if (Date.now() - lastExtend > 45_000) {
        lastExtend = Date.now()
        void extendSession()
      }
    }
    const onVisibility = () => {
      if (document.hidden) {
        hiddenAt = Date.now()
      } else {
        if (hiddenAt && Date.now() - hiddenAt >= delayMs) lock()
        else reset()
        hiddenAt = null
      }
    }

    const events = ['pointerdown', 'keydown', 'scroll'] as const
    events.forEach((e) => window.addEventListener(e, reset, { passive: true }))
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      window.clearTimeout(timer)
      events.forEach((e) => window.removeEventListener(e, reset))
      document.removeEventListener('visibilitychange', onVisibility)
    }
  }, [phase, delayMinutes, lock, extendSession])
}

export default function App() {
  const phase = useStore((s) => s.phase)
  const init = useStore((s) => s.init)
  useResolvedTheme()
  useAutoLock()

  useEffect(() => {
    void init()
  }, [init])

  if (phase === 'loading') {
    return (
      <div className="lock-screen" role="status">
        Chargement…
      </div>
    )
  }

  if (phase === 'setup' || phase === 'locked') {
    return <LockPage mode={phase} />
  }

  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<DashboardPage />} />
        <Route path="transactions" element={<TransactionsPage />} />
        <Route path="budgets" element={<BudgetsPage />} />
        <Route path="plans" element={<FundingPage />} />
        <Route path="plans/nouveau" element={<FundingEditorPage />} />
        <Route path="plans/:id" element={<FundingDetailPage />} />
        <Route path="plans/:id/modifier" element={<FundingEditorPage />} />
        <Route path="prelevements" element={<SubscriptionsPage />} />
        <Route path="comptes" element={<AccountsPage />} />
        <Route path="categories" element={<CategoriesPage />} />
        <Route path="reglages" element={<SettingsPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  )
}
