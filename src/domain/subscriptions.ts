// Prélèvements récurrents : abonnements et prêts. Calculs de coût mensuel
// équivalent, de reste à rembourser d'un prêt, et de synthèse (par compte, par
// catégorie avec comparaison au budget, indispensable ou non).

import { alive, type AppData, type Subscription } from './types.ts'
import type { Period } from './periods.ts'

/** Coût mensuel équivalent d'un prélèvement (un annuel est ramené au mois). */
export function monthlyEquivalent(sub: Subscription): number {
  if (sub.kind === 'loan') return sub.amount
  return sub.frequency === 'yearly' ? Math.round(sub.amount / 12) : sub.amount
}

/** Un prélèvement compte-t-il encore (actif, non supprimé, prêt non échu) ? */
export function isCommitmentActive(sub: Subscription, todayIso: string): boolean {
  if (sub.deletedAt || !sub.active) return false
  if (sub.kind === 'loan' && sub.endDate && sub.endDate < todayIso) return false
  return true
}

/** Nombre de mensualités restantes jusqu'à la date de fin (mois courant inclus). */
export function monthsUntil(endIso: string, todayIso: string): number {
  const [ey, em] = endIso.split('-').map(Number)
  const [ty, tm] = todayIso.split('-').map(Number)
  return Math.max(0, (ey - ty) * 12 + (em - tm) + 1)
}

/** Reste à rembourser d'un prêt = mensualités restantes × montant. */
export function loanRemaining(sub: Subscription, todayIso: string): number {
  if (sub.kind !== 'loan' || !sub.endDate) return 0
  return monthsUntil(sub.endDate, todayIso) * sub.amount
}

/** Un prélèvement annuel tombe-t-il dans la période budgétaire donnée ? */
function yearlyOccursInPeriod(sub: Subscription, period: Period): boolean {
  if (!sub.dueMonth) return false
  const day = String(Math.min(sub.dayOfMonth, 28)).padStart(2, '0')
  const month = String(sub.dueMonth).padStart(2, '0')
  const years = new Set([Number(period.start.slice(0, 4)), Number(period.end.slice(0, 4))])
  for (const y of years) {
    const occ = `${y}-${month}-${day}`
    if (occ >= period.start && occ <= period.end) return true
  }
  return false
}

/**
 * Montant d'un prélèvement imputable à une période budgétaire :
 * un mensuel compte son montant chaque mois ; un annuel ne compte QUE dans le
 * mois de son échéance (impact plein le mois venu, plutôt que lissé).
 */
export function committedForPeriod(sub: Subscription, period: Period, todayIso: string): number {
  if (!isCommitmentActive(sub, todayIso)) return 0
  if (sub.kind === 'loan') return sub.amount
  if (sub.frequency === 'yearly') return yearlyOccursInPeriod(sub, period) ? sub.amount : 0
  return sub.amount
}

/**
 * Total des abonnements dont la catégorie fait partie de `categoryIds`
 * (la catégorie du budget et ses sous-catégories), imputé à la période.
 */
export function committedForCategories(
  subscriptions: Subscription[],
  categoryIds: Set<string>,
  period: Period,
  todayIso: string,
): number {
  return alive(subscriptions)
    .filter((s) => s.categoryId && categoryIds.has(s.categoryId))
    .reduce((sum, s) => sum + committedForPeriod(s, period, todayIso), 0)
}

export interface AccountTotal {
  accountId: string | null
  name: string
  monthly: number
}

export interface CategoryTotal {
  categoryId: string | null
  name: string
  monthly: number
  /** Budget mensuel de la catégorie, ou null si aucun. */
  budget: number | null
  /** Vrai si le total des prélèvements dépasse le budget de la catégorie. */
  over: boolean
}

export interface CommitmentSummary {
  activeCount: number
  totalMonthly: number
  essentialMonthly: number
  nonEssentialMonthly: number
  byAccount: AccountTotal[]
  byCategory: CategoryTotal[]
}

/**
 * Synthèse des prélèvements. Les totaux sont des moyennes mensuelles (un annuel
 * y compte 1/12). Si `period` est fourni, la répartition par catégorie utilise
 * l'imputation réelle à cette période (un annuel compte plein dans son mois),
 * afin de coller à ce qu'affiche la page Budgets.
 */
export function computeCommitmentSummary(
  data: AppData,
  todayIso: string,
  period?: Period,
): CommitmentSummary {
  const active = alive(data.subscriptions).filter((s) => isCommitmentActive(s, todayIso))
  const accountName = new Map(alive(data.accounts).map((a) => [a.id, a.name]))
  const categoryName = new Map(alive(data.categories).map((c) => [c.id, c.name]))
  const budgetByCategory = new Map(
    alive(data.budgets).map((b) => [b.categoryId, b.monthlyAmount]),
  )

  let totalMonthly = 0
  let essentialMonthly = 0
  let nonEssentialMonthly = 0
  const accountMap = new Map<string | null, number>()
  const categoryMap = new Map<string | null, number>()

  for (const sub of active) {
    const m = monthlyEquivalent(sub)
    totalMonthly += m
    if (sub.essential) essentialMonthly += m
    else nonEssentialMonthly += m
    accountMap.set(sub.accountId, (accountMap.get(sub.accountId) ?? 0) + m)
    // Par catégorie : imputation réelle à la période si elle est connue.
    const forCategory = period ? committedForPeriod(sub, period, todayIso) : m
    if (forCategory > 0) {
      categoryMap.set(sub.categoryId, (categoryMap.get(sub.categoryId) ?? 0) + forCategory)
    }
  }

  const byAccount: AccountTotal[] = [...accountMap.entries()]
    .map(([accountId, monthly]) => ({
      accountId,
      name: accountId ? (accountName.get(accountId) ?? 'Compte supprimé') : 'Sans compte',
      monthly,
    }))
    .sort((a, b) => b.monthly - a.monthly)

  const byCategory: CategoryTotal[] = [...categoryMap.entries()]
    .map(([categoryId, monthly]) => {
      const budget = categoryId ? (budgetByCategory.get(categoryId) ?? null) : null
      return {
        categoryId,
        name: categoryId ? (categoryName.get(categoryId) ?? 'Catégorie supprimée') : 'Sans catégorie',
        monthly,
        budget,
        over: budget !== null && monthly > budget,
      }
    })
    .sort((a, b) => b.monthly - a.monthly)

  return {
    activeCount: active.length,
    totalMonthly,
    essentialMonthly,
    nonEssentialMonthly,
    byAccount,
    byCategory,
  }
}
