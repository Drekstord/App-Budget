// Prélèvements récurrents : abonnements et prêts. Calculs de coût mensuel
// équivalent, de reste à rembourser d'un prêt, et de synthèse (par compte, par
// catégorie avec comparaison au budget, indispensable ou non).

import { alive, type AppData, type Subscription } from './types.ts'

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

export function computeCommitmentSummary(data: AppData, todayIso: string): CommitmentSummary {
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
    categoryMap.set(sub.categoryId, (categoryMap.get(sub.categoryId) ?? 0) + m)
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
