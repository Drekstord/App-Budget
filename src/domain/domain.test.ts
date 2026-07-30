import { describe, expect, it } from 'vitest'
import { formatEUR, parseAmountToCents, centsToInput } from './money.ts'
import { inPeriod, lastPeriods, periodForDate, periodProgress, shiftPeriod } from './periods.ts'
import {
  accountBalance,
  budgetAllocation,
  budgetStatuses,
  computeKpis,
  expensesByRootCategory,
  initialBalanceForTarget,
  totalBalance,
} from './stats.ts'
import { computeAdvice } from './advice.ts'
import { createBackup, parseBackup, BackupError } from './backup.ts'
import { stamp, type Account, type AppData, type Budget, type Category, type Transaction, DEFAULT_SETTINGS } from './types.ts'

function account(overrides: Partial<Account> = {}): Account {
  return {
    ...stamp(),
    name: 'Compte',
    type: 'checking',
    initialBalance: 0,
    overdraft: 0,
    icon: '🏦',
    archived: false,
    ...overrides,
  }
}

function category(overrides: Partial<Category> = {}): Category {
  return {
    ...stamp(),
    name: 'Cat',
    kind: 'expense',
    parentId: null,
    icon: '🏷️',
    colorSlot: 1,
    ...overrides,
  }
}

function tx(overrides: Partial<Transaction> = {}): Transaction {
  return {
    ...stamp(),
    type: 'expense',
    amount: 1000,
    date: '2026-07-02',
    accountId: 'a1',
    toAccountId: null,
    categoryId: null,
    note: '',
    payee: '',
    ...overrides,
  }
}

function budget(overrides: Partial<Budget> = {}): Budget {
  return { ...stamp(), categoryId: 'c1', monthlyAmount: 30000, ...overrides }
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

describe('money', () => {
  it('formate en euros français', () => {
    expect(formatEUR(123456)).toMatch(/1[\s ]234,56[\s ]€/)
  })

  it('interprète les saisies françaises', () => {
    expect(parseAmountToCents('12,50')).toBe(1250)
    expect(parseAmountToCents('1 234,56')).toBe(123456)
    expect(parseAmountToCents('12.5')).toBe(1250)
    expect(parseAmountToCents('300')).toBe(30000)
    expect(parseAmountToCents('12,345')).toBeNull()
    expect(parseAmountToCents('abc')).toBeNull()
    expect(parseAmountToCents('')).toBeNull()
  })

  it('fait l’aller-retour saisie ↔ centimes', () => {
    expect(parseAmountToCents(centsToInput(1250))).toBe(1250)
  })
})

describe('periods', () => {
  it('mois civil quand le début est le 1er', () => {
    const p = periodForDate('2026-07-15', 1)
    expect(p.start).toBe('2026-07-01')
    expect(p.end).toBe('2026-07-31')
  })

  it('mois budgétaire décalé (paie le 28)', () => {
    const before = periodForDate('2026-07-15', 28)
    expect(before.start).toBe('2026-06-28')
    expect(before.end).toBe('2026-07-27')
    const after = periodForDate('2026-07-28', 28)
    expect(after.start).toBe('2026-07-28')
    expect(after.end).toBe('2026-08-27')
  })

  it('décale correctement les périodes', () => {
    const p = periodForDate('2026-07-15', 1)
    expect(shiftPeriod(p, -1, 1).start).toBe('2026-06-01')
    expect(shiftPeriod(p, 1, 1).start).toBe('2026-08-01')
  })

  it('liste les n dernières périodes en ordre chronologique', () => {
    const list = lastPeriods(3, 1, '2026-07-15')
    expect(list.map((p) => p.key)).toEqual(['2026-05', '2026-06', '2026-07'])
  })

  it('teste l’appartenance et la progression', () => {
    const p = periodForDate('2026-07-15', 1)
    expect(inPeriod('2026-07-01', p)).toBe(true)
    expect(inPeriod('2026-06-30', p)).toBe(false)
    const { totalDays, elapsedDays } = periodProgress(p, '2026-07-15')
    expect(totalDays).toBe(31)
    expect(elapsedDays).toBe(15)
  })
})

describe('stats', () => {
  const a1 = account({ id: 'a1', initialBalance: 100000 })
  const a2 = account({ id: 'a2' })

  it('calcule le solde d’un compte avec virements', () => {
    const txs = [
      tx({ accountId: 'a1', amount: 2000 }), // dépense
      tx({ type: 'income', accountId: 'a1', amount: 5000 }),
      tx({ type: 'transfer', accountId: 'a1', toAccountId: 'a2', amount: 10000 }),
    ]
    expect(accountBalance(a1, txs)).toBe(100000 - 2000 + 5000 - 10000)
    expect(accountBalance(a2, txs)).toBe(10000)
    // Un virement interne ne change pas le solde total.
    expect(totalBalance([a1, a2], txs)).toBe(100000 + 3000)
  })

  it('ignore les transactions supprimées (tombstones)', () => {
    const dead = tx({ accountId: 'a1', amount: 2000, deletedAt: '2026-07-02T00:00:00Z' })
    expect(accountBalance(a1, [dead])).toBe(100000)
  })

  it('recale le solde de départ pour atteindre un solde actuel donné', () => {
    const txs = [
      tx({ accountId: 'a1', amount: 2000 }),
      tx({ type: 'income', accountId: 'a1', amount: 5000 }),
    ]
    // On veut afficher 250,00 € aujourd'hui malgré les mouvements existants.
    const start = initialBalanceForTarget(a1, txs, 25000)
    expect(accountBalance({ ...a1, initialBalance: start }, txs)).toBe(25000)
  })

  it('accepte un solde actuel négatif (découvert)', () => {
    const txs = [tx({ accountId: 'a1', amount: 2000 })]
    const start = initialBalanceForTarget(a1, txs, -31050)
    expect(accountBalance({ ...a1, initialBalance: start }, txs)).toBe(-31050)
  })

  it('sans mouvement, le solde de départ est le solde visé', () => {
    expect(initialBalanceForTarget(a2, [], -5000)).toBe(-5000)
  })

  it('agrège les dépenses par catégorie racine', () => {
    const parent = category({ id: 'c1', name: 'Alimentation' })
    const child = category({ id: 'c2', name: 'Courses', parentId: 'c1' })
    const data = appData({
      categories: [parent, child],
      transactions: [
        tx({ categoryId: 'c1', amount: 1000 }),
        tx({ categoryId: 'c2', amount: 500 }),
      ],
    })
    const period = periodForDate('2026-07-15', 1)
    const slices = expensesByRootCategory(data, period)
    expect(slices).toHaveLength(1)
    expect(slices[0].category.id).toBe('c1')
    expect(slices[0].amount).toBe(1500)
  })

  it('évalue les statuts de budget avec seuil et projection', () => {
    const cat = category({ id: 'c1', name: 'Courses' })
    const data = appData({
      categories: [cat],
      budgets: [budget({ categoryId: 'c1', monthlyAmount: 30000 })],
      transactions: [tx({ categoryId: 'c1', amount: 25000, date: '2026-07-05' })],
    })
    const period = periodForDate('2026-07-10', 1)
    const [status] = budgetStatuses(data, period, '2026-07-10')
    expect(status.level).toBe('warning') // 83 % ≥ seuil 80 %
    // 2500/j × 31j ≫ 300 € : dépassement projeté dans la période.
    expect(status.projectedOverDate).not.toBeNull()
  })

  it('calcule les KPI', () => {
    const cat = category({ id: 'c1' })
    const data = appData({
      accounts: [account({ id: 'a1', initialBalance: 0 })],
      categories: [cat],
      budgets: [budget({ categoryId: 'c1', monthlyAmount: 20000 })],
      transactions: [
        tx({ categoryId: 'c1', amount: 5000 }),
        tx({ type: 'income', amount: 20000 }),
      ],
    })
    const period = periodForDate('2026-07-15', 1)
    const kpis = computeKpis(data, period, '2026-07-15')
    expect(kpis.periodExpense).toBe(5000)
    expect(kpis.periodIncome).toBe(20000)
    expect(kpis.remainingBudget).toBe(15000)
    expect(kpis.savingsRate).toBe(75)
  })
})

describe('budgetAllocation (reste à attribuer)', () => {
  it('utilise le revenu de référence saisi', () => {
    const cat = category({ id: 'c1' })
    const data = appData({
      categories: [cat],
      budgets: [budget({ categoryId: 'c1', monthlyAmount: 30000 })],
      settings: { ...DEFAULT_SETTINGS, monthlyIncomeReference: 200000 },
    })
    const a = budgetAllocation(data, '2026-07-15')
    expect(a.referenceIsManual).toBe(true)
    expect(a.reference).toBe(200000)
    expect(a.totalBudgeted).toBe(30000)
    expect(a.remaining).toBe(170000)
  })

  it('retombe sur la moyenne des 3 mois précédents sans référence saisie', () => {
    const cat = category({ id: 'c1' })
    const data = appData({
      categories: [cat],
      transactions: [
        tx({ type: 'income', amount: 200000, date: '2026-04-10' }),
        tx({ type: 'income', amount: 200000, date: '2026-05-10' }),
        tx({ type: 'income', amount: 200000, date: '2026-06-10' }),
        tx({ type: 'income', amount: 999999, date: '2026-07-10' }), // mois courant : exclu
      ],
    })
    const a = budgetAllocation(data, '2026-07-15')
    expect(a.referenceIsManual).toBe(false)
    expect(a.reference).toBe(200000)
  })
})

describe('advice', () => {
  it('signale un budget dépassé', () => {
    const cat = category({ id: 'c1', name: 'Courses' })
    const data = appData({
      categories: [cat],
      budgets: [budget({ categoryId: 'c1', monthlyAmount: 10000 })],
      transactions: [tx({ categoryId: 'c1', amount: 15000, date: '2026-07-03' })],
    })
    const advice = computeAdvice(data, '2026-07-10')
    expect(advice.some((a) => a.severity === 'critical' && a.text.includes('Courses'))).toBe(true)
  })

  it('signale des dépenses supérieures aux revenus', () => {
    const cat = category({ id: 'c1' })
    const data = appData({
      categories: [cat],
      transactions: [
        tx({ categoryId: 'c1', amount: 30000, date: '2026-07-03' }),
        tx({ type: 'income', amount: 10000, date: '2026-07-01' }),
      ],
    })
    const advice = computeAdvice(data, '2026-07-10')
    expect(advice.some((a) => a.id === 'negative-savings')).toBe(true)
  })

  it('suggère un budget sur un gros poste non budgété', () => {
    const cat = category({ id: 'c1', name: 'Transport' })
    const data = appData({
      categories: [cat],
      transactions: [tx({ categoryId: 'c1', amount: 12000, date: '2026-07-02' })],
    })
    const advice = computeAdvice(data, '2026-07-10')
    expect(advice.some((a) => a.id === 'suggest-c1')).toBe(true)
  })

  it('détecte une catégorie en dérive vs la moyenne des mois passés', () => {
    const cat = category({ id: 'c1', name: 'Loisirs' })
    const data = appData({
      categories: [cat],
      transactions: [
        // Historique : ~100 €/mois sur 3 mois.
        tx({ categoryId: 'c1', amount: 10000, date: '2026-04-10' }),
        tx({ categoryId: 'c1', amount: 10000, date: '2026-05-10' }),
        tx({ categoryId: 'c1', amount: 10000, date: '2026-06-10' }),
        // Mois courant : 200 € dès le 15 → projection bien au-dessus.
        tx({ categoryId: 'c1', amount: 20000, date: '2026-07-05' }),
      ],
    })
    const advice = computeAdvice(data, '2026-07-15')
    expect(advice.some((a) => a.id === 'drift-c1')).toBe(true)
  })
})

describe('backup', () => {
  it('fait l’aller-retour export → import', () => {
    const cat = category({ id: 'c1' })
    const data = appData({
      accounts: [account({ id: 'a1' })],
      categories: [cat],
      transactions: [tx({ categoryId: 'c1' })],
      budgets: [budget()],
    })
    const restored = parseBackup(JSON.stringify(createBackup(data)))
    expect(restored.transactions).toHaveLength(1)
    expect(restored.accounts[0].id).toBe('a1')
    expect(restored.settings.monthStartDay).toBe(DEFAULT_SETTINGS.monthStartDay)
  })

  it('rejette les fichiers invalides', () => {
    expect(() => parseBackup('pas du json')).toThrow(BackupError)
    expect(() => parseBackup('{"format":"autre"}')).toThrow(BackupError)
    expect(() =>
      parseBackup(JSON.stringify({ format: 'app-budget-backup', version: 99, data: {} })),
    ).toThrow(BackupError)
  })
})
