import { describe, expect, it } from 'vitest'
import { suggestPayday } from './payday.ts'
import { stamp, type AppData, type Transaction, DEFAULT_SETTINGS } from './types.ts'

function tx(overrides: Partial<Transaction> = {}): Transaction {
  return {
    ...stamp(),
    type: 'income',
    amount: 250000,
    date: '2026-07-28',
    accountId: 'a1',
    toAccountId: null,
    categoryId: null,
    note: '',
    payee: 'ACME SA',
    ...overrides,
  }
}

function data(transactions: Transaction[]): AppData {
  return {
    accounts: [],
    categories: [],
    transactions,
    budgets: [],
    fundingPlans: [],
    subscriptions: [],
    settings: { ...DEFAULT_SETTINGS },
  }
}

describe('suggestPayday', () => {
  it('repère le jour de paie régulier', () => {
    const s = suggestPayday(
      data([
        tx({ date: '2026-05-28' }),
        tx({ date: '2026-06-28' }),
        tx({ date: '2026-07-28' }),
      ]),
      '2026-07-31',
    )
    expect(s?.day).toBe(28)
    expect(s?.monthsAnalysed).toBe(3)
    expect(s?.label).toBe('ACME SA')
    expect(s?.averageAmount).toBe(250000)
    expect(s?.endOfMonth).toBe(false)
  })

  it('ignore les petits revenus d’appoint', () => {
    const s = suggestPayday(
      data([
        tx({ date: '2026-06-05', amount: 8000, payee: 'Remboursement' }),
        tx({ date: '2026-06-28' }),
        tx({ date: '2026-07-03', amount: 12000, payee: 'Vinted' }),
        tx({ date: '2026-07-28' }),
      ]),
      '2026-07-31',
    )
    expect(s?.day).toBe(28)
  })

  it('propose « dernier jour » quand la paie tombe en fin de mois', () => {
    const s = suggestPayday(
      data([
        tx({ date: '2026-04-30' }),
        tx({ date: '2026-05-31' }),
        tx({ date: '2026-06-30' }),
      ]),
      '2026-07-15',
    )
    expect(s?.day).toBe(31)
    expect(s?.endOfMonth).toBe(true)
  })

  it('suit le versement le plus récent en cas d’égalité', () => {
    const s = suggestPayday(
      data([tx({ date: '2026-06-05' }), tx({ date: '2026-07-10' })]),
      '2026-07-31',
    )
    expect(s?.day).toBe(10)
  })

  it('ne propose rien avec moins de deux mois d’historique', () => {
    expect(suggestPayday(data([tx({ date: '2026-07-28' })]), '2026-07-31')).toBeNull()
    expect(suggestPayday(data([]), '2026-07-31')).toBeNull()
  })

  it('ignore les dépenses, les opérations supprimées et les revenus trop anciens', () => {
    const s = suggestPayday(
      data([
        tx({ date: '2026-07-03', type: 'expense', amount: 900000 }),
        tx({ date: '2026-06-15', deletedAt: '2026-06-16T00:00:00Z', amount: 900000 }),
        tx({ date: '2024-01-20', amount: 900000 }),
        tx({ date: '2026-06-28' }),
        tx({ date: '2026-07-28' }),
      ]),
      '2026-07-31',
    )
    expect(s?.day).toBe(28)
    expect(s?.monthsAnalysed).toBe(2)
  })
})
