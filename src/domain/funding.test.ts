import { describe, expect, it } from 'vitest'
import { computeFundingPlan } from './funding.ts'
import {
  stamp,
  type Account,
  type FundingAccountRule,
  type FundingFlow,
  type FundingPlan,
  type Transaction,
} from './types.ts'

function account(id: string, initialBalance: number, name = id): Account {
  return { ...stamp(), id, name, type: 'checking', initialBalance, icon: '🏦', archived: false }
}

function rule(accountId: string, priority: number, extra: Partial<FundingAccountRule> = {}): FundingAccountRule {
  return { accountId, priority, keepMin: 0, excluded: false, ...extra }
}

function flow(
  label: string,
  amount: number,
  date: string,
  recurrence: 'once' | 'monthly',
  kind: 'fixed' | 'variable' = 'fixed',
): FundingFlow {
  return { id: label, label, amount, date, recurrence, kind }
}

function plan(overrides: Partial<FundingPlan> = {}): FundingPlan {
  return {
    ...stamp(),
    name: 'Plan',
    targetAmount: 500000, // 5 000 €
    targetLabel: 'Voiture',
    targetDate: '2026-12-15',
    accountRules: [],
    incomes: [],
    expenseEvents: [],
    ...overrides,
  }
}

const NO_TX: Transaction[] = []

describe('computeFundingPlan — allocation et priorités', () => {
  it('ponctionne les comptes dans l’ordre de priorité', () => {
    const accounts = [account('courant', 200000, 'Courant'), account('epargne', 1000000, 'Épargne')]
    const p = plan({
      targetAmount: 300000,
      accountRules: [rule('courant', 0), rule('epargne', 1)],
    })
    const r = computeFundingPlan(p, { accounts, transactions: NO_TX }, '2026-07-23')
    const courant = r.draws.find((d) => d.accountId === 'courant')!
    const epargne = r.draws.find((d) => d.accountId === 'epargne')!
    expect(courant.allocated).toBe(200000) // vidé en premier
    expect(epargne.allocated).toBe(100000) // complément
    expect(r.feasibility).toBe('covered_now')
    expect(r.coveredNow).toBe(300000)
  })

  it('respecte un montant à préserver (protéger l’épargne)', () => {
    const accounts = [account('courant', 100000, 'Courant'), account('epargne', 500000, 'Épargne')]
    const p = plan({
      targetAmount: 400000,
      // On veut garder au moins 3 000 € sur l'épargne.
      accountRules: [rule('courant', 0), rule('epargne', 1, { keepMin: 300000 })],
    })
    const r = computeFundingPlan(p, { accounts, transactions: NO_TX }, '2026-07-23')
    // Mobilisable = 1 000 (courant) + 2 000 (épargne au-dessus de la réserve) = 3 000 €
    expect(r.drawableNow).toBe(300000)
    const epargne = r.draws.find((d) => d.accountId === 'epargne')!
    expect(epargne.drawable).toBe(200000)
  })

  it('exclut totalement un compte protégé', () => {
    const accounts = [account('courant', 100000), account('epargne', 900000)]
    const p = plan({
      targetAmount: 300000,
      accountRules: [rule('courant', 0), rule('epargne', 1, { excluded: true })],
    })
    const r = computeFundingPlan(p, { accounts, transactions: NO_TX }, '2026-07-23')
    expect(r.draws.find((d) => d.accountId === 'epargne')!.drawable).toBe(0)
    expect(r.drawableNow).toBe(100000)
  })
})

describe('computeFundingPlan — projection et faisabilité', () => {
  const accounts = [account('courant', 100000, 'Courant')]

  it('atteignable avec l’épargne des revenus fixes', () => {
    const p = plan({
      targetAmount: 500000,
      targetDate: '2026-12-15',
      accountRules: [rule('courant', 0)],
      // Salaire de 2 000 €/mois à partir d'août : Aug..Dec = 5 × 2000 = 10 000 €
      incomes: [flow('Salaire', 200000, '2026-08-05', 'monthly', 'fixed')],
    })
    const r = computeFundingPlan(p, { accounts, transactions: NO_TX }, '2026-07-23')
    expect(r.monthsRemaining).toBe(5)
    expect(r.totalFixedIncome).toBe(1000000)
    expect(r.feasibility).toBe('feasible')
    // Manque à constituer = 5 000 − 1 000 (mobilisable) = 4 000 € sur 5 mois
    expect(r.shortfallNow).toBe(400000)
    expect(r.requiredMonthlySaving).toBe(80000)
  })

  it('soustrait les événements de dépense (loyer, vacances)', () => {
    const p = plan({
      targetAmount: 500000,
      accountRules: [rule('courant', 0)],
      incomes: [flow('Salaire', 200000, '2026-08-05', 'monthly', 'fixed')],
      expenseEvents: [
        flow('Loyer', 80000, '2026-08-01', 'monthly'),
        flow('Vacances', 150000, '2026-08-10', 'once'),
      ],
    })
    const r = computeFundingPlan(p, { accounts, transactions: NO_TX }, '2026-07-23')
    // Loyer 800 €×5 = 4 000 € + vacances 1 500 € = 5 500 € d'événements
    expect(r.totalExpenseEvents).toBe(550000)
    // Projeté = 1 000 (mobilisable) + 10 000 (salaire) − 5 500 = 5 500 € ≥ 5 000 €
    expect(r.projectedAtTarget).toBe(550000)
    expect(r.feasibility).toBe('feasible')
  })

  it('n’est atteignable qu’avec les revenus variables', () => {
    const p = plan({
      targetAmount: 500000,
      accountRules: [rule('courant', 0)],
      incomes: [
        flow('Salaire', 70000, '2026-08-05', 'monthly', 'fixed'), // 700×5 = 3 500
        flow('Primes', 100000, '2026-10-15', 'once', 'variable'), // +1 000
      ],
    })
    const r = computeFundingPlan(p, { accounts, transactions: NO_TX }, '2026-07-23')
    // Fixe : 1 000 + 3 500 = 4 500 < 5 000 ; avec variable : 5 500 ≥ 5 000
    expect(r.projectedAtTarget).toBe(450000)
    expect(r.projectedAtTargetWithVariable).toBe(550000)
    expect(r.feasibility).toBe('feasible_variable')
  })

  it('détecte un objectif hors de portée', () => {
    const p = plan({
      targetAmount: 500000,
      accountRules: [rule('courant', 0)],
      incomes: [flow('Salaire', 50000, '2026-08-05', 'monthly', 'fixed')], // 500×5 = 2 500
    })
    const r = computeFundingPlan(p, { accounts, transactions: NO_TX }, '2026-07-23')
    expect(r.feasibility).toBe('infeasible')
    expect(r.warnings[0].severity).toBe('critical')
  })

  it('signale un point de tension de trésorerie avant l’échéance', () => {
    const p = plan({
      targetAmount: 100000,
      targetDate: '2026-12-15',
      accountRules: [rule('courant', 0)], // 1 000 € mobilisable
      expenseEvents: [flow('Gros achat', 300000, '2026-09-10', 'once')], // 3 000 € en sept
    })
    const r = computeFundingPlan(p, { accounts, transactions: NO_TX }, '2026-07-23')
    expect(r.warnings.some((w) => w.id.startsWith('danger-'))).toBe(true)
  })

  it('produit une timeline du mois courant à l’échéance', () => {
    const p = plan({ targetDate: '2026-10-15', accountRules: [rule('courant', 0)] })
    const r = computeFundingPlan(p, { accounts, transactions: NO_TX }, '2026-07-23')
    expect(r.timeline).toHaveLength(4) // juil, août, sept, oct
    expect(r.timeline[0].monthKey).toBe('2026-07')
    expect(r.timeline[3].monthKey).toBe('2026-10')
    expect(r.timeline[3].isTargetMonth).toBe(true)
  })

  it('ne compte pas les occurrences déjà passées (déjà dans les soldes)', () => {
    const p = plan({
      accountRules: [rule('courant', 0)],
      // Salaire le 5 : celui de juillet est déjà passé (today = 23 juil).
      incomes: [flow('Salaire', 200000, '2026-07-05', 'monthly', 'fixed')],
    })
    const r = computeFundingPlan(p, { accounts, transactions: NO_TX }, '2026-07-23')
    // Août..Déc = 5 mois × 2 000 €, pas le 5 juillet déjà encaissé.
    expect(r.totalFixedIncome).toBe(1000000)
    expect(r.timeline[0].fixedIncome).toBe(0)
  })
})
