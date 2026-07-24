// Plan de financement d'une grosse dépense (« amortissement »).
//
// À partir des comptes réels (soldes calculés en direct), de règles de
// priorité/protection par compte, des revenus futurs (fixes et variables) et
// des événements de dépense à venir (loyer, vacances…), on produit un plan
// explicable : ce qui est mobilisable tout de suite, combien épargner par mois,
// la faisabilité à l'échéance, et les points de tension de trésorerie.
//
// Tout est calculé localement, en centimes entiers.

import {
  alive,
  type Account,
  type AppData,
  type FundingFlow,
  type FundingPlan,
  type Transaction,
} from './types.ts'
import { accountBalance } from './stats.ts'

export interface AccountDraw {
  accountId: string
  name: string
  icon: string
  balance: number
  keepMin: number
  excluded: boolean
  priority: number
  /** Découvert autorisé pris en compte pour ce plan (0 si non utilisé). */
  overdraft: number
  /** Déjà réservé sur ce compte par des projets plus urgents (échéance plus proche). */
  reservedByOthers: number
  /** Mobilisable = excluded ? 0 : max(0, solde − réservé ailleurs − à préserver + découvert). */
  drawable: number
  /** Ponctionné pour couvrir la dépense dès maintenant. */
  allocated: number
  /** Part de l'allocation prise dans le découvert autorisé (solde passé sous zéro). */
  fromOverdraft: number
}

export type Feasibility = 'covered_now' | 'feasible' | 'feasible_variable' | 'infeasible'

export interface TimelinePoint {
  monthKey: string
  label: string
  fixedIncome: number
  variableIncome: number
  expenseEvents: number
  isTargetMonth: boolean
  /** Solde mobilisable projeté (revenus fixes seuls), avant paiement de la cible. */
  projectedFixed: number
  /** Idem en comptant aussi les revenus variables. */
  projectedWithVariable: number
}

export interface FundingWarning {
  id: string
  severity: 'critical' | 'warning' | 'info' | 'good'
  text: string
}

export interface FundingResult {
  targetAmount: number
  targetDate: string
  monthsRemaining: number
  drawableNow: number
  draws: AccountDraw[]
  coveredNow: number
  shortfallNow: number
  totalFixedIncome: number
  totalVariableIncome: number
  totalExpenseEvents: number
  projectedAtTarget: number
  projectedAtTargetWithVariable: number
  feasibility: Feasibility
  /** Ce qui manque à l'échéance dans le pire scénario prudent (revenus fixes). */
  missingAmount: number
  /** Épargne mensuelle à constituer pour combler le manque d'ici l'échéance. */
  requiredMonthlySaving: number
  /** Marge nette moyenne par mois (revenus fixes − événements de dépense). */
  averageMonthlyNet: number
  /** Total réservé sur les comptes de ce plan par des projets plus urgents. */
  reservedByOtherPlans: number
  /** Noms des projets plus urgents qui réservent une partie de sa trésorerie. */
  aheadPlanNames: string[]
  timeline: TimelinePoint[]
  warnings: FundingWarning[]
}

const MONTH_LABEL = new Intl.DateTimeFormat('fr-FR', { month: 'short', year: '2-digit' })

function monthIndex(iso: string): number {
  const [y, m] = iso.split('-').map(Number)
  return y * 12 + (m - 1)
}

function monthKeyOf(index: number): string {
  const y = Math.floor(index / 12)
  const m = (index % 12) + 1
  return `${y}-${String(m).padStart(2, '0')}`
}

function lastDayOfMonth(index: number): number {
  const y = Math.floor(index / 12)
  const m = index % 12
  return new Date(y, m + 1, 0).getDate()
}

/** Date d'occurrence (YYYY-MM-DD) d'un flux dans le mois d'index donné. */
function occurrenceDate(flow: FundingFlow, index: number): string {
  const day = Number(flow.date.split('-')[2])
  const clamped = Math.min(day, lastDayOfMonth(index))
  return `${monthKeyOf(index)}-${String(clamped).padStart(2, '0')}`
}

/**
 * Somme d'un flux tombant dans le mois `index`, en ne comptant que les
 * occurrences strictement postérieures à `afterIso` et au plus tard à `throughIso`.
 * Les occurrences passées sont déjà reflétées dans les soldes des comptes.
 */
function flowInMonth(flow: FundingFlow, index: number, afterIso: string, throughIso: string): number {
  const startIndex = monthIndex(flow.date)
  if (flow.recurrence === 'once') {
    if (startIndex !== index) return 0
    return flow.date > afterIso && flow.date <= throughIso ? flow.amount : 0
  }
  // Récurrent : actif à partir de son mois de départ.
  if (index < startIndex) return 0
  // Annuel : uniquement le même mois calendaire que la première occurrence.
  if (flow.recurrence === 'yearly' && index % 12 !== startIndex % 12) return 0
  const occ = occurrenceDate(flow, index)
  return occ > afterIso && occ <= throughIso ? flow.amount : 0
}

export function computeAccountDraws(
  plan: FundingPlan,
  accounts: Account[],
  transactions: Transaction[],
  /** Montant déjà réservé par compte par des projets plus urgents. */
  reserved?: Map<string, number>,
): AccountDraw[] {
  const byId = new Map(alive(accounts).map((a) => [a.id, a]))
  const draws: AccountDraw[] = []
  for (const rule of plan.accountRules) {
    const account = byId.get(rule.accountId)
    if (!account) continue
    const balance = accountBalance(account, transactions)
    const reservedByOthers = reserved?.get(account.id) ?? 0
    // Solde effectif = solde réel diminué de ce que les projets plus urgents ont réservé.
    const effectiveBalance = balance - reservedByOthers
    // Découvert autorisé pris en compte si le compte en dispose et que le plan
    // l'autorise (par défaut oui).
    const overdraft = rule.useOverdraft === false ? 0 : (account.overdraft ?? 0)
    const drawable = rule.excluded ? 0 : Math.max(0, effectiveBalance - rule.keepMin + overdraft)
    draws.push({
      accountId: account.id,
      name: account.name,
      icon: account.icon,
      balance,
      keepMin: rule.keepMin,
      excluded: rule.excluded,
      priority: rule.priority,
      overdraft,
      reservedByOthers,
      drawable,
      allocated: 0,
      fromOverdraft: 0,
    })
  }
  return draws.sort((a, b) => a.priority - b.priority)
}

export function computeFundingPlan(
  plan: FundingPlan,
  data: Pick<AppData, 'accounts' | 'transactions'>,
  todayIso: string,
  /** Trésorerie déjà réservée, par compte, par des projets plus urgents. */
  reserved?: Map<string, number>,
): FundingResult {
  const draws = computeAccountDraws(plan, data.accounts, data.transactions, reserved)

  // Couverture immédiate : ponction dans l'ordre de priorité, protections respectées.
  let need = plan.targetAmount
  let overdraftUsed = 0
  for (const draw of draws) {
    const take = Math.min(need, draw.drawable)
    draw.allocated = take
    // Ce qui dépasse le solde effectif disponible (hors découvert) est pris sur le découvert.
    const ownAvailable = Math.max(0, draw.balance - draw.reservedByOthers - draw.keepMin)
    draw.fromOverdraft = Math.max(0, take - ownAvailable)
    overdraftUsed += draw.fromOverdraft
    need -= take
  }
  const reservedByOtherPlans = draws.reduce((sum, d) => sum + d.reservedByOthers, 0)
  const drawableNow = draws.reduce((sum, d) => sum + d.drawable, 0)
  const coveredNow = Math.min(plan.targetAmount, drawableNow)
  const shortfallNow = Math.max(0, plan.targetAmount - drawableNow)

  // Buckets mensuels du mois courant au mois de l'échéance.
  const startIndex = monthIndex(todayIso)
  const targetIndex = Math.max(startIndex, monthIndex(plan.targetDate))
  const monthsRemaining = targetIndex - startIndex

  const timeline: TimelinePoint[] = []
  let runningFixed = drawableNow
  let runningVar = drawableNow
  let totalFixedIncome = 0
  let totalVariableIncome = 0
  let totalExpenseEvents = 0
  const warnings: FundingWarning[] = []

  for (let index = startIndex; index <= targetIndex; index++) {
    let fixedIncome = 0
    let variableIncome = 0
    for (const flow of plan.incomes) {
      const amount = flowInMonth(flow, index, todayIso, plan.targetDate)
      if (flow.kind === 'variable') variableIncome += amount
      else fixedIncome += amount
    }
    let expenseEvents = 0
    for (const flow of plan.expenseEvents) {
      expenseEvents += flowInMonth(flow, index, todayIso, plan.targetDate)
    }

    totalFixedIncome += fixedIncome
    totalVariableIncome += variableIncome
    totalExpenseEvents += expenseEvents

    runningFixed += fixedIncome - expenseEvents
    runningVar += fixedIncome + variableIncome - expenseEvents

    const isTargetMonth = index === targetIndex
    timeline.push({
      monthKey: monthKeyOf(index),
      label: MONTH_LABEL.format(new Date(monthKeyOf(index) + '-01T00:00:00')),
      fixedIncome,
      variableIncome,
      expenseEvents,
      isTargetMonth,
      projectedFixed: runningFixed,
      projectedWithVariable: runningVar,
    })

    // Point de tension : le mobilisable prudent passe sous zéro avant l'échéance
    // (il faudrait entamer une réserve protégée ou se retrouver à découvert).
    if (!isTargetMonth && runningFixed < 0) {
      warnings.push({
        id: `danger-${monthKeyOf(index)}`,
        severity: 'warning',
        text: `En ${MONTH_LABEL.format(new Date(monthKeyOf(index) + '-01T00:00:00'))}, après tes dépenses prévues, il te manquerait ${formatCents(-runningFixed)} de trésorerie mobilisable.`,
      })
    }
  }

  const projectedAtTarget = drawableNow + totalFixedIncome - totalExpenseEvents
  const projectedAtTargetWithVariable = projectedAtTarget + totalVariableIncome

  let feasibility: Feasibility
  if (drawableNow >= plan.targetAmount) feasibility = 'covered_now'
  else if (projectedAtTarget >= plan.targetAmount) feasibility = 'feasible'
  else if (projectedAtTargetWithVariable >= plan.targetAmount) feasibility = 'feasible_variable'
  else feasibility = 'infeasible'

  const missingAmount = Math.max(0, plan.targetAmount - projectedAtTarget)
  const requiredMonthlySaving =
    monthsRemaining <= 0 ? shortfallNow : Math.ceil(shortfallNow / monthsRemaining)
  const averageMonthlyNet =
    monthsRemaining > 0 ? Math.round((totalFixedIncome - totalExpenseEvents) / monthsRemaining) : 0

  // Verdict en tête de liste.
  if (feasibility === 'covered_now') {
    warnings.unshift({
      id: 'verdict',
      severity: 'good',
      text: `Tu peux financer « ${plan.targetLabel} » dès maintenant en mobilisant ${formatCents(coveredNow)}.`,
    })
  } else if (feasibility === 'feasible') {
    const tight = requiredMonthlySaving > averageMonthlyNet && averageMonthlyNet > 0
    warnings.unshift({
      id: 'verdict',
      severity: tight ? 'warning' : 'good',
      text: tight
        ? `Objectif atteignable mais serré : il faut mettre ${formatCents(requiredMonthlySaving)}/mois de côté, pour une marge nette d'environ ${formatCents(averageMonthlyNet)}/mois.`
        : `Objectif atteignable : mets ${formatCents(requiredMonthlySaving)}/mois de côté d'ici l'échéance.`,
    })
  } else if (feasibility === 'feasible_variable') {
    warnings.unshift({
      id: 'verdict',
      severity: 'warning',
      text: `Atteignable seulement en comptant tes revenus variables (non garantis). Sans eux, il manquerait ${formatCents(missingAmount)} à l'échéance.`,
    })
  } else {
    const stillMissing = Math.max(0, plan.targetAmount - projectedAtTargetWithVariable)
    warnings.unshift({
      id: 'verdict',
      severity: 'critical',
      text: `Objectif hors de portée d'ici l'échéance : il manquerait ${formatCents(stillMissing)}, même en comptant tes revenus variables. Repousse la date, réduis le montant, ou ajoute des revenus.`,
    })
  }

  if (overdraftUsed > 0) {
    warnings.push({
      id: 'overdraft',
      severity: 'info',
      text: `Ce plan mobilise ${formatCents(overdraftUsed)} de découvert autorisé (sans frais) : le compte passera temporairement en négatif.`,
    })
  }

  return {
    targetAmount: plan.targetAmount,
    targetDate: plan.targetDate,
    monthsRemaining,
    drawableNow,
    draws,
    coveredNow,
    shortfallNow,
    totalFixedIncome,
    totalVariableIncome,
    totalExpenseEvents,
    projectedAtTarget,
    projectedAtTargetWithVariable,
    feasibility,
    missingAmount,
    requiredMonthlySaving,
    averageMonthlyNet,
    reservedByOtherPlans,
    aheadPlanNames: [],
    timeline,
    warnings,
  }
}

export interface PlanWithResult {
  plan: FundingPlan
  result: FundingResult
}

function daysBetween(fromIso: string, toIso: string): number {
  const [fy, fm, fd] = fromIso.split('-').map(Number)
  const [ty, tm, td] = toIso.split('-').map(Number)
  return Math.round((Date.UTC(ty, tm - 1, td) - Date.UTC(fy, fm - 1, fd)) / 86_400_000)
}

export interface ActionStep {
  planId: string
  label: string
  targetDate: string
  order: number
  /** À mobiliser tout de suite depuis les comptes. */
  mobilizeNow: number
  /** À mettre de côté chaque mois d'ici l'échéance. */
  monthlySaving: number
  /** Équivalent par jour (pour un repère concret). */
  dailySaving: number
  feasibility: Feasibility
}

export interface ActionPlan {
  steps: ActionStep[]
  totalMobilizeNow: number
  totalMonthlySaving: number
  totalDailySaving: number
  anyInfeasible: boolean
}

/**
 * « Marche à suivre » concrète sur l'ensemble des projets : quoi mobiliser tout
 * de suite et combien épargner par mois (et par jour), projet par projet, dans
 * l'ordre des échéances.
 */
export function fundingActionPlan(
  data: Pick<AppData, 'accounts' | 'transactions' | 'fundingPlans'>,
  todayIso: string,
): ActionPlan {
  const plans = computeFundingPlans(data, todayIso)
  const steps: ActionStep[] = plans.map(({ plan, result }, i) => {
    const days = Math.max(1, daysBetween(todayIso, plan.targetDate))
    const dailySaving = result.shortfallNow > 0 ? Math.ceil(result.shortfallNow / days) : 0
    return {
      planId: plan.id,
      label: plan.targetLabel,
      targetDate: plan.targetDate,
      order: i + 1,
      mobilizeNow: result.coveredNow,
      monthlySaving: result.requiredMonthlySaving,
      dailySaving,
      feasibility: result.feasibility,
    }
  })
  return {
    steps,
    totalMobilizeNow: steps.reduce((s, x) => s + x.mobilizeNow, 0),
    totalMonthlySaving: steps.reduce((s, x) => s + x.monthlySaving, 0),
    totalDailySaving: steps.reduce((s, x) => s + x.dailySaving, 0),
    anyInfeasible: steps.some((x) => x.feasibility === 'infeasible'),
  }
}

/**
 * Calcule tous les plans en les rendant conscients les uns des autres : ils sont
 * traités dans l'ordre des échéances (le plus proche d'abord), et chaque plan
 * réserve la trésorerie qu'il mobilise, si bien que les plans plus lointains ne
 * voient que ce qui reste. Pas de double comptage de l'argent partagé.
 */
export function computeFundingPlans(
  data: Pick<AppData, 'accounts' | 'transactions' | 'fundingPlans'>,
  todayIso: string,
): PlanWithResult[] {
  const plans = alive(data.fundingPlans)
    .slice()
    .sort((a, b) => a.targetDate.localeCompare(b.targetDate) || a.createdAt.localeCompare(b.createdAt))

  const reserved = new Map<string, number>()
  const contributors = new Map<string, string[]>()
  const results: PlanWithResult[] = []

  for (const plan of plans) {
    const result = computeFundingPlan(plan, data, todayIso, reserved)

    // Noms des projets plus urgents qui réservent sur les comptes de ce plan.
    const ahead = new Set<string>()
    for (const draw of result.draws) {
      if (draw.reservedByOthers > 0) {
        for (const name of contributors.get(draw.accountId) ?? []) ahead.add(name)
      }
    }
    result.aheadPlanNames = [...ahead]
    results.push({ plan, result })

    // Ce plan réserve à son tour ce qu'il mobilise pour les plans suivants.
    for (const draw of result.draws) {
      if (draw.allocated <= 0) continue
      reserved.set(draw.accountId, (reserved.get(draw.accountId) ?? 0) + draw.allocated)
      const list = contributors.get(draw.accountId) ?? []
      if (!list.includes(plan.name)) list.push(plan.name)
      contributors.set(draw.accountId, list)
    }
  }
  return results
}

// Format local minimal (sans dépendre de money.ts pour rester autonome/testable).
function formatCents(cents: number): string {
  return new Intl.NumberFormat('fr-FR', {
    style: 'currency',
    currency: 'EUR',
    maximumFractionDigits: 0,
  }).format(cents / 100)
}
