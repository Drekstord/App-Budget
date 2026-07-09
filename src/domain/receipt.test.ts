import { describe, expect, it } from 'vitest'
import { parseReceipt } from './receipt.ts'
import { computeTransactionAlerts } from './alerts.ts'
import {
  DEFAULT_SETTINGS,
  stamp,
  type AppData,
  type Budget,
  type Category,
  type Transaction,
} from './types.ts'

const CARREFOUR = `
CARREFOUR MARKET
12 RUE DE LA PAIX
75002 PARIS
Tel: 01.42.00.00.00

LAIT DEMI ECREME     1,15
PAIN DE MIE          2,30
POULET FERMIER      12,90
YAOURTS X8           3,45

TOTAL A PAYER       19,80
CB                  19,80
LE 03/07/2026 A 18:42
MERCI DE VOTRE VISITE
`

const PHARMACIE = `
PHARMACIE DU CENTRE
DOLIPRANE 1000        4,50
SPRAY NASAL           7,20
MONTANT TTC          11,70
ESPECES              20,00
RENDU                 8,30
Le 15 juillet 2026
`

const ILLISIBLE = `
~~~ ??? ~~~
###
`

describe('parseReceipt', () => {
  it('extrait total, enseigne, date et catégorie d’un ticket de supermarché', () => {
    const r = parseReceipt(CARREFOUR)
    expect(r.amountCents).toBe(1980)
    expect(r.merchant).toBe('Carrefour')
    expect(r.categoryHint).toBe('Alimentation')
    expect(r.date).toBe('2026-07-03')
  })

  it('préfère le total TTC au billet tendu en espèces et au rendu de monnaie', () => {
    const r = parseReceipt(PHARMACIE)
    expect(r.amountCents).toBe(1170)
    expect(r.merchant).toBe('Pharmacie')
    expect(r.categoryHint).toBe('Santé')
    expect(r.date).toBe('2026-07-15')
  })

  it('extrait une date textuelle française', () => {
    expect(parseReceipt('Achat du 3 juillet 2026\nTOTAL 5,00').date).toBe('2026-07-03')
  })

  it('rejette les dates invalides', () => {
    expect(parseReceipt('45/13/2026\nTOTAL 5,00').date).toBeNull()
  })

  it('retourne des champs nuls sur un ticket illisible', () => {
    const r = parseReceipt(ILLISIBLE)
    expect(r.amountCents).toBeNull()
    expect(r.date).toBeNull()
    expect(r.merchant).toBeNull()
  })

  it('devine l’enseigne inconnue depuis la première ligne propre', () => {
    const r = parseReceipt('BOUCHERIE MARTIN\nSIRET 123\nTOTAL 24,90')
    expect(r.merchant).toBe('BOUCHERIE MARTIN')
    expect(r.amountCents).toBe(2490)
  })
})

function category(overrides: Partial<Category> = {}): Category {
  return {
    ...stamp(),
    name: 'Courses',
    kind: 'expense',
    parentId: null,
    icon: '🛒',
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
    categoryId: 'c1',
    note: '',
    payee: '',
    ...overrides,
  }
}

function budget(overrides: Partial<Budget> = {}): Budget {
  return { ...stamp(), categoryId: 'c1', monthlyAmount: 10000, ...overrides }
}

function appData(overrides: Partial<AppData> = {}): AppData {
  return {
    accounts: [],
    categories: [category({ id: 'c1' })],
    transactions: [],
    budgets: [],
    settings: { ...DEFAULT_SETTINGS },
    ...overrides,
  }
}

describe('computeTransactionAlerts', () => {
  const newExpense = (amount: number) => ({
    type: 'expense' as const,
    amount,
    date: '2026-07-10',
    categoryId: 'c1',
  })

  it('alerte quand la dépense franchit le plafond du budget', () => {
    const data = appData({
      budgets: [budget()],
      transactions: [tx({ amount: 6000 })],
    })
    const alerts = computeTransactionAlerts(data, newExpense(5000), '2026-07-10')
    expect(alerts.some((a) => a.id.startsWith('budget-over') && a.severity === 'critical')).toBe(true)
  })

  it('alerte au franchissement du seuil d’avertissement (80 %)', () => {
    const data = appData({
      budgets: [budget()],
      transactions: [tx({ amount: 5000 })],
    })
    const alerts = computeTransactionAlerts(data, newExpense(3500), '2026-07-10')
    expect(alerts.some((a) => a.id.startsWith('budget-warn') && a.severity === 'warning')).toBe(true)
  })

  it('ne ré-alerte pas si le budget était déjà dépassé', () => {
    const data = appData({
      budgets: [budget()],
      transactions: [tx({ amount: 12000 })],
    })
    const alerts = computeTransactionAlerts(data, newExpense(500), '2026-07-10')
    expect(alerts.filter((a) => a.id.startsWith('budget-'))).toHaveLength(0)
  })

  it('signale une grosse dépense au-delà du seuil configuré', () => {
    const data = appData()
    const alerts = computeTransactionAlerts(data, newExpense(15000), '2026-07-10')
    expect(alerts.some((a) => a.id === 'large-expense')).toBe(true)
  })

  it('reste silencieux pour une petite dépense sans budget concerné', () => {
    const data = appData()
    expect(computeTransactionAlerts(data, newExpense(500), '2026-07-10')).toHaveLength(0)
  })

  it('ignore les revenus et les opérations hors période courante', () => {
    const data = appData({ budgets: [budget()], transactions: [tx({ amount: 9000 })] })
    expect(
      computeTransactionAlerts(data, { ...newExpense(5000), type: 'income' }, '2026-07-10'),
    ).toHaveLength(0)
    expect(
      computeTransactionAlerts(data, { ...newExpense(5000), date: '2026-05-10' }, '2026-07-10'),
    ).toHaveLength(0)
  })

  it('couvre les sous-catégories via le budget du parent', () => {
    const data = appData({
      categories: [category({ id: 'c1' }), category({ id: 'c2', parentId: 'c1', name: 'Bio' })],
      budgets: [budget()],
      transactions: [tx({ amount: 9000 })],
    })
    const alerts = computeTransactionAlerts(
      data,
      { ...newExpense(2000), categoryId: 'c2' },
      '2026-07-10',
    )
    expect(alerts.some((a) => a.id.startsWith('budget-over'))).toBe(true)
  })
})
