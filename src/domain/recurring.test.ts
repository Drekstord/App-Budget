import { describe, expect, it } from 'vitest'
import { dueOccurrences, nextOccurrence, pendingSubscriptionTransactions } from './recurring.ts'
import { realAvailability } from './stats.ts'
import { periodForDate } from './periods.ts'
import {
  DEFAULT_SETTINGS,
  stamp,
  type AppData,
  type Budget,
  type Category,
  type Subscription,
  type Transaction,
} from './types.ts'

function sub(overrides: Partial<Subscription> = {}): Subscription {
  return {
    ...stamp(),
    kind: 'subscription',
    name: 'Abo',
    amount: 1000,
    frequency: 'monthly',
    dayOfMonth: 5,
    dueMonth: null,
    categoryId: null,
    essential: false,
    accountId: 'a1',
    active: true,
    endDate: null,
    createdAt: '2026-05-01T00:00:00Z',
    ...overrides,
  }
}

function tx(overrides: Partial<Transaction> = {}): Transaction {
  return {
    ...stamp(),
    type: 'expense',
    amount: 1000,
    date: '2026-07-10',
    accountId: 'a1',
    toAccountId: null,
    categoryId: null,
    note: '',
    payee: '',
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

describe('dueOccurrences', () => {
  it('une échéance par mois au jour indiqué', () => {
    expect(dueOccurrences(sub({ dayOfMonth: 5 }), '2026-05-01', '2026-07-15')).toEqual([
      '2026-05-05',
      '2026-06-05',
      '2026-07-05',
    ])
  })

  it('ramène un jour trop grand au dernier jour du mois', () => {
    expect(dueOccurrences(sub({ dayOfMonth: 31 }), '2026-02-01', '2026-02-28')).toEqual(['2026-02-28'])
  })

  it('un annuel ne tombe que dans son mois', () => {
    const yearly = sub({ frequency: 'yearly', dueMonth: 3, dayOfMonth: 10 })
    expect(dueOccurrences(yearly, '2026-01-01', '2027-06-30')).toEqual(['2026-03-10', '2027-03-10'])
  })

  it('un prêt s’arrête à sa dernière mensualité', () => {
    const loan = sub({ kind: 'loan', dayOfMonth: 1, endDate: '2026-06-01' })
    expect(dueOccurrences(loan, '2026-04-01', '2026-09-01')).toEqual([
      '2026-04-01',
      '2026-05-01',
      '2026-06-01',
    ])
  })

  it('donne la prochaine échéance à venir', () => {
    expect(nextOccurrence(sub({ dayOfMonth: 20 }), '2026-07-15')).toBe('2026-07-20')
    expect(nextOccurrence(sub({ dayOfMonth: 5 }), '2026-07-15')).toBe('2026-08-05')
  })
})

describe('pendingSubscriptionTransactions', () => {
  it('rattrape les échéances arrivées depuis la création', () => {
    const data = appData({
      subscriptions: [sub({ name: 'Netflix', amount: 1300, createdAt: '2026-05-01T00:00:00Z' })],
    })
    const created = pendingSubscriptionTransactions(data, '2026-07-15')
    expect(created.map((t) => t.date)).toEqual(['2026-05-05', '2026-06-05', '2026-07-05'])
    expect(created[0]).toMatchObject({
      type: 'expense',
      amount: 1300,
      payee: 'Netflix',
      note: 'Prélèvement automatique',
    })
    expect(created[0].subscriptionId).toBeDefined()
  })

  it('ne génère rien pour les échéances futures', () => {
    const data = appData({
      subscriptions: [sub({ dayOfMonth: 28, createdAt: '2026-07-01T00:00:00Z' })],
    })
    expect(pendingSubscriptionTransactions(data, '2026-07-15')).toHaveLength(0)
  })

  it('est idempotent : deux appels ne créent pas de doublon', () => {
    const data = appData({
      subscriptions: [sub({ createdAt: '2026-06-01T00:00:00Z' })],
    })
    const first = pendingSubscriptionTransactions(data, '2026-07-15')
    expect(first).toHaveLength(2)
    const after = { ...data, transactions: [...data.transactions, ...first] }
    expect(pendingSubscriptionTransactions(after, '2026-07-15')).toHaveLength(0)
  })

  it('ne recrée pas une opération supprimée par l’utilisateur', () => {
    const s = sub({ createdAt: '2026-07-01T00:00:00Z' })
    const deleted = tx({
      date: '2026-07-05',
      subscriptionId: s.id,
      occurrence: '2026-07-05',
      deletedAt: '2026-07-06T00:00:00Z',
    })
    const data = appData({ subscriptions: [s], transactions: [deleted] })
    expect(pendingSubscriptionTransactions(data, '2026-07-15')).toHaveLength(0)
  })

  it('ignore un abonnement en pause', () => {
    const data = appData({
      subscriptions: [sub({ active: false, createdAt: '2026-05-01T00:00:00Z' })],
    })
    expect(pendingSubscriptionTransactions(data, '2026-07-15')).toHaveLength(0)
  })
})

describe('realAvailability (disponible réel)', () => {
  const courses: Category = {
    ...stamp(),
    id: 'courses',
    name: 'Courses',
    kind: 'expense',
    parentId: null,
    icon: '🛒',
    colorSlot: 1,
  }
  const loisirs: Category = { ...courses, id: 'loisirs', name: 'Loisirs', icon: '🎮' }
  const budgets: Budget[] = [{ ...stamp(), categoryId: 'courses', monthlyAmount: 30000 }]

  it('soustrait les dépenses hors budget du disponible réel', () => {
    const data = appData({
      categories: [courses, loisirs],
      budgets,
      // 100 € dans le budget Courses + 50 € en Loisirs (sans budget)
      transactions: [
        tx({ categoryId: 'courses', amount: 10000 }),
        tx({ categoryId: 'loisirs', amount: 5000 }),
      ],
      settings: { ...DEFAULT_SETTINGS, monthlyIncomeReference: 200000 },
    })
    const r = realAvailability(data, periodForDate('2026-07-15', 1), '2026-07-15')
    expect(r.spentBudgeted).toBe(10000)
    expect(r.spentUnbudgeted).toBe(5000)
    // Reste réservé dans l'enveloppe Courses : 300 − 100 = 200 €
    expect(r.budgetReserved).toBe(20000)
    // 2 000 − 100 − 50 − 200 = 1 650 €
    expect(r.realRemaining).toBe(165000)
    expect(r.unbudgeted[0]).toMatchObject({ amount: 5000 })
    expect(r.unbudgeted[0].category?.name).toBe('Loisirs')
  })

  it('compte les dépenses sans catégorie comme hors budget', () => {
    const data = appData({
      categories: [courses],
      budgets,
      transactions: [tx({ categoryId: null, amount: 2500 })],
      settings: { ...DEFAULT_SETTINGS, monthlyIncomeReference: 100000 },
    })
    const r = realAvailability(data, periodForDate('2026-07-15', 1), '2026-07-15')
    expect(r.spentUnbudgeted).toBe(2500)
    expect(r.unbudgeted[0].category).toBeNull()
  })

  it('une sous-catégorie est couverte par le budget de son parent', () => {
    const bio: Category = { ...courses, id: 'bio', name: 'Bio', parentId: 'courses' }
    const data = appData({
      categories: [courses, bio],
      budgets,
      transactions: [tx({ categoryId: 'bio', amount: 4000 })],
      settings: { ...DEFAULT_SETTINGS, monthlyIncomeReference: 100000 },
    })
    const r = realAvailability(data, periodForDate('2026-07-15', 1), '2026-07-15')
    expect(r.spentBudgeted).toBe(4000)
    expect(r.spentUnbudgeted).toBe(0)
  })
})
