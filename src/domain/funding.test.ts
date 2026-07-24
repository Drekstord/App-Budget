import { describe, expect, it } from 'vitest'
import { computeFundingPlan, computeFundingPlans, fundingActionPlan } from './funding.ts'
import {
  stamp,
  type Account,
  type FundingAccountRule,
  type FundingFlow,
  type FundingPlan,
  type Transaction,
} from './types.ts'

function account(id: string, initialBalance: number, name = id, overdraft = 0): Account {
  return {
    ...stamp(),
    id,
    name,
    type: 'checking',
    initialBalance,
    overdraft,
    icon: '🏦',
    archived: false,
  }
}

function rule(accountId: string, priority: number, extra: Partial<FundingAccountRule> = {}): FundingAccountRule {
  return { accountId, priority, keepMin: 0, excluded: false, ...extra }
}

function flow(
  label: string,
  amount: number,
  date: string,
  recurrence: 'once' | 'monthly' | 'yearly',
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

  it('inclut le découvert autorisé dans le mobilisable', () => {
    // Courant 500 € + 2 000 € de découvert autorisé.
    const accounts = [account('courant', 50000, 'Courant', 200000)]
    const p = plan({ targetAmount: 250000, accountRules: [rule('courant', 0)] })
    const r = computeFundingPlan(p, { accounts, transactions: NO_TX }, '2026-07-23')
    const courant = r.draws.find((d) => d.accountId === 'courant')!
    expect(courant.drawable).toBe(250000) // 500 + 2 000 de découvert
    expect(courant.allocated).toBe(250000)
    expect(courant.fromOverdraft).toBe(200000) // 2 000 € pris sur le découvert
    expect(r.warnings.some((w) => w.id === 'overdraft')).toBe(true)
  })

  it('ignore le découvert si le plan le désactive', () => {
    const accounts = [account('courant', 50000, 'Courant', 200000)]
    const p = plan({
      targetAmount: 250000,
      accountRules: [rule('courant', 0, { useOverdraft: false })],
    })
    const r = computeFundingPlan(p, { accounts, transactions: NO_TX }, '2026-07-23')
    expect(r.draws.find((d) => d.accountId === 'courant')!.drawable).toBe(50000)
    expect(r.drawableNow).toBe(50000)
  })

  it('ne compte pas le découvert comme pris tant que le solde propre suffit', () => {
    const accounts = [account('courant', 300000, 'Courant', 200000)]
    const p = plan({ targetAmount: 250000, accountRules: [rule('courant', 0)] })
    const r = computeFundingPlan(p, { accounts, transactions: NO_TX }, '2026-07-23')
    const courant = r.draws.find((d) => d.accountId === 'courant')!
    expect(courant.allocated).toBe(250000)
    expect(courant.fromOverdraft).toBe(0) // le solde de 3 000 € suffit
    expect(r.warnings.some((w) => w.id === 'overdraft')).toBe(false)
  })
})

describe('computeFundingPlans — projets conscients les uns des autres', () => {
  it('le projet le plus urgent se sert en premier, l’autre voit le reste', () => {
    const accounts = [account('courant', 300000, 'Courant')] // 3 000 € partagés
    const urgent = plan({
      id: 'urgent',
      name: 'Urgent',
      targetAmount: 200000,
      targetDate: '2026-09-15',
      accountRules: [rule('courant', 0)],
    })
    const later = plan({
      id: 'later',
      name: 'Plus tard',
      targetAmount: 200000,
      targetDate: '2026-12-15',
      accountRules: [rule('courant', 0)],
    })
    const data = { accounts, transactions: NO_TX, fundingPlans: [later, urgent] }
    const results = computeFundingPlans(data, '2026-07-23')

    // Traités par échéance : Urgent (sept.) d'abord.
    expect(results.map((r) => r.plan.id)).toEqual(['urgent', 'later'])
    const urgentRes = results[0].result
    const laterRes = results[1].result
    // Urgent mobilise 2 000 € ; il reste 1 000 € pour l'autre.
    expect(urgentRes.drawableNow).toBe(300000)
    expect(urgentRes.coveredNow).toBe(200000)
    expect(laterRes.reservedByOtherPlans).toBe(200000)
    expect(laterRes.drawableNow).toBe(100000) // 3 000 − 2 000 réservés
    expect(laterRes.aheadPlanNames).toContain('Urgent')
  })

  it('produit une marche à suivre ordonnée par échéance', () => {
    const accounts = [account('courant', 100000, 'Courant')]
    const urgent = plan({ id: 'u', name: 'Urgent', targetLabel: 'Urgent', targetAmount: 300000, targetDate: '2026-09-15', accountRules: [rule('courant', 0)] })
    const later = plan({ id: 'l', name: 'Plus tard', targetLabel: 'Plus tard', targetAmount: 300000, targetDate: '2026-12-15', accountRules: [rule('courant', 0)] })
    const ap = fundingActionPlan({ accounts, transactions: NO_TX, fundingPlans: [later, urgent] }, '2026-07-23')
    // Ordonné : Urgent (sept.) d'abord.
    expect(ap.steps.map((s) => s.planId)).toEqual(['u', 'l'])
    expect(ap.steps[0].order).toBe(1)
    // Urgent mobilise 1 000 € maintenant, puis épargne mensuelle et journalière > 0.
    expect(ap.steps[0].mobilizeNow).toBe(100000)
    expect(ap.steps[0].monthlySaving).toBeGreaterThan(0)
    expect(ap.steps[0].dailySaving).toBeGreaterThan(0)
    expect(ap.totalMonthlySaving).toBe(ap.steps[0].monthlySaving + ap.steps[1].monthlySaving)
  })

  it('ne double-compte jamais l’argent partagé entre projets', () => {
    const accounts = [account('courant', 500000, 'Courant')]
    const a = plan({ id: 'a', name: 'A', targetAmount: 400000, targetDate: '2026-08-15', accountRules: [rule('courant', 0)] })
    const b = plan({ id: 'b', name: 'B', targetAmount: 400000, targetDate: '2026-10-15', accountRules: [rule('courant', 0)] })
    const results = computeFundingPlans({ accounts, transactions: NO_TX, fundingPlans: [a, b] }, '2026-07-23')
    const totalCovered = results.reduce((s, r) => s + r.result.coveredNow, 0)
    expect(totalCovered).toBe(500000) // jamais plus que les 5 000 € réels
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

  it('compte une prime annuelle si elle tombe avant l’échéance', () => {
    const p = plan({
      targetAmount: 500000,
      targetDate: '2026-12-15',
      accountRules: [rule('courant', 0)],
      // Prime annuelle de 1 000 € chaque 5 décembre → une occurrence (5 déc. 2026,
      // avant l'échéance du 15).
      incomes: [flow('Prime', 100000, '2025-12-05', 'yearly', 'variable')],
    })
    const r = computeFundingPlan(p, { accounts, transactions: NO_TX }, '2026-07-23')
    expect(r.totalVariableIncome).toBe(100000)
    const dec = r.timeline.find((t) => t.monthKey === '2026-12')!
    expect(dec.variableIncome).toBe(100000)
    // Pas de double comptage sur les autres mois.
    expect(r.timeline.filter((t) => t.variableIncome > 0)).toHaveLength(1)
  })

  it('ignore une prime annuelle qui tombe après l’échéance', () => {
    const p = plan({
      targetAmount: 500000,
      targetDate: '2026-10-15',
      accountRules: [rule('courant', 0)],
      incomes: [flow('Prime', 100000, '2025-12-20', 'yearly', 'variable')], // déc. > oct.
    })
    const r = computeFundingPlan(p, { accounts, transactions: NO_TX }, '2026-07-23')
    expect(r.totalVariableIncome).toBe(0)
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
