import { describe, expect, it } from 'vitest'
import {
  computeCommitmentSummary,
  isCommitmentActive,
  loanRemaining,
  monthlyEquivalent,
  monthsUntil,
} from './subscriptions.ts'
import {
  DEFAULT_SETTINGS,
  stamp,
  type Account,
  type AppData,
  type Budget,
  type Category,
  type Subscription,
} from './types.ts'

function sub(overrides: Partial<Subscription> = {}): Subscription {
  return {
    ...stamp(),
    kind: 'subscription',
    name: 'Abo',
    amount: 1000,
    frequency: 'monthly',
    dayOfMonth: 5,
    categoryId: null,
    essential: false,
    accountId: null,
    active: true,
    endDate: null,
    ...overrides,
  }
}

function appData(overrides: Partial<AppData> = {}): AppData {
  return {
    accounts: [],
    categories: [],
    transactions: [],
    budgets: [],
    fundingPlans: [],
    subscriptions: [],
    settings: { ...DEFAULT_SETTINGS },
    ...overrides,
  }
}

describe('monthlyEquivalent', () => {
  it('ramène un abonnement annuel au mois', () => {
    expect(monthlyEquivalent(sub({ frequency: 'yearly', amount: 12000 }))).toBe(1000)
    expect(monthlyEquivalent(sub({ frequency: 'monthly', amount: 1500 }))).toBe(1500)
  })
  it('un prêt compte sa mensualité', () => {
    expect(monthlyEquivalent(sub({ kind: 'loan', amount: 25000 }))).toBe(25000)
  })
})

describe('prêt : reste à rembourser', () => {
  it('compte les mensualités restantes jusqu’à la fin (mois courant inclus)', () => {
    expect(monthsUntil('2026-12-15', '2026-07-23')).toBe(6) // juil→déc
    const loan = sub({ kind: 'loan', amount: 25000, endDate: '2026-12-15' })
    expect(loanRemaining(loan, '2026-07-23')).toBe(150000) // 6 × 250 €
  })
  it('un prêt échu ne compte plus', () => {
    const loan = sub({ kind: 'loan', amount: 25000, endDate: '2026-06-15' })
    expect(isCommitmentActive(loan, '2026-07-23')).toBe(false)
  })
})

describe('computeCommitmentSummary', () => {
  const accounts: Account[] = [
    { ...stamp(), id: 'a1', name: 'Compte prélèv.', type: 'checking', initialBalance: 0, overdraft: 0, icon: '🏦', archived: false },
  ]
  const categories: Category[] = [
    { ...stamp(), id: 'abo', name: 'Abonnements', kind: 'expense', parentId: null, icon: '📱', colorSlot: 6 },
  ]

  it('agrège total, indispensables, par compte et par catégorie', () => {
    const data = appData({
      accounts,
      categories,
      subscriptions: [
        sub({ name: 'Netflix', amount: 1300, categoryId: 'abo', accountId: 'a1', essential: false }),
        sub({ name: 'Assurance', amount: 4500, categoryId: 'abo', accountId: 'a1', essential: true }),
        sub({ name: 'Prime annuelle assurance', amount: 12000, frequency: 'yearly', categoryId: 'abo', accountId: 'a1', essential: true }),
        sub({ name: 'En pause', amount: 9999, active: false }),
      ],
    })
    const s = computeCommitmentSummary(data, '2026-07-23')
    // 13 + 45 + 10 (120/12) = 68 € ; l'inactif est ignoré
    expect(s.totalMonthly).toBe(1300 + 4500 + 1000)
    expect(s.essentialMonthly).toBe(4500 + 1000)
    expect(s.nonEssentialMonthly).toBe(1300)
    expect(s.activeCount).toBe(3)
    expect(s.byAccount[0]).toMatchObject({ accountId: 'a1', monthly: 6800 })
  })

  it('signale un dépassement de budget par catégorie', () => {
    const budgets: Budget[] = [{ ...stamp(), categoryId: 'abo', monthlyAmount: 5000 }]
    const data = appData({
      accounts,
      categories,
      budgets,
      subscriptions: [
        sub({ amount: 4000, categoryId: 'abo' }),
        sub({ amount: 2000, categoryId: 'abo' }),
      ],
    })
    const s = computeCommitmentSummary(data, '2026-07-23')
    const cat = s.byCategory.find((c) => c.categoryId === 'abo')!
    expect(cat.monthly).toBe(6000)
    expect(cat.budget).toBe(5000)
    expect(cat.over).toBe(true) // 60 € > 50 €
  })
})
