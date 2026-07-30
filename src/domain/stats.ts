// Agrégations et indicateurs calculés localement à partir des transactions.

import { alive, type Account, type AppData, type Budget, type Category, type Transaction } from './types.ts'
import { inPeriod, lastPeriods, periodProgress, type Period } from './periods.ts'

export function accountBalance(account: Account, transactions: Transaction[]): number {
  let balance = account.initialBalance
  for (const t of alive(transactions)) {
    if (t.type === 'expense' && t.accountId === account.id) balance -= t.amount
    else if (t.type === 'income' && t.accountId === account.id) balance += t.amount
    else if (t.type === 'transfer') {
      if (t.accountId === account.id) balance -= t.amount
      if (t.toAccountId === account.id) balance += t.amount
    }
  }
  return balance
}

export function totalBalance(accounts: Account[], transactions: Transaction[]): number {
  return alive(accounts)
    .filter((a) => !a.archived)
    .reduce((sum, a) => sum + accountBalance(a, transactions), 0)
}

function activeTransactions(transactions: Transaction[], period: Period): Transaction[] {
  return alive(transactions).filter((t) => inPeriod(t.date, period))
}

export function periodTotals(
  transactions: Transaction[],
  period: Period,
): { expense: number; income: number } {
  let expense = 0
  let income = 0
  for (const t of activeTransactions(transactions, period)) {
    if (t.type === 'expense') expense += t.amount
    else if (t.type === 'income') income += t.amount
  }
  return { expense, income }
}

/** Ids d'une catégorie et de toutes ses sous-catégories vivantes. */
export function categoryWithChildren(categoryId: string, categories: Category[]): Set<string> {
  const ids = new Set<string>([categoryId])
  let changed = true
  while (changed) {
    changed = false
    for (const c of alive(categories)) {
      if (c.parentId && ids.has(c.parentId) && !ids.has(c.id)) {
        ids.add(c.id)
        changed = true
      }
    }
  }
  return ids
}

export function spentForCategory(
  categoryId: string,
  data: Pick<AppData, 'transactions' | 'categories'>,
  period: Period,
): number {
  const ids = categoryWithChildren(categoryId, data.categories)
  return activeTransactions(data.transactions, period)
    .filter((t) => t.type === 'expense' && t.categoryId && ids.has(t.categoryId))
    .reduce((sum, t) => sum + t.amount, 0)
}

export interface CategorySlice {
  category: Category
  amount: number
}

/**
 * Dépenses de la période par catégorie racine (les sous-catégories sont
 * rattachées à leur parent), triées par montant décroissant.
 */
export function expensesByRootCategory(data: AppData, period: Period): CategorySlice[] {
  const categories = alive(data.categories)
  const byId = new Map(categories.map((c) => [c.id, c]))
  const rootOf = (id: string): Category | undefined => {
    let current = byId.get(id)
    while (current?.parentId) {
      const parent = byId.get(current.parentId)
      if (!parent) break
      current = parent
    }
    return current
  }
  const sums = new Map<string, number>()
  for (const t of activeTransactions(data.transactions, period)) {
    if (t.type !== 'expense' || !t.categoryId) continue
    const root = rootOf(t.categoryId)
    if (!root) continue
    sums.set(root.id, (sums.get(root.id) ?? 0) + t.amount)
  }
  return [...sums.entries()]
    .map(([id, amount]) => ({ category: byId.get(id)!, amount }))
    .filter((s) => s.category)
    .sort((a, b) => b.amount - a.amount)
}

export interface PeriodSeriesPoint {
  period: Period
  expense: number
  income: number
}

export function periodSeries(data: AppData, nPeriods: number, todayIso?: string): PeriodSeriesPoint[] {
  return lastPeriods(nPeriods, data.settings.monthStartDay, todayIso).map((period) => ({
    period,
    ...periodTotals(data.transactions, period),
  }))
}

/** Revenu mensuel moyen sur les `nMonths` périodes précédant la période courante. */
export function averageMonthlyIncome(data: AppData, nMonths = 3, todayIso?: string): number {
  // On exclut la période courante (souvent partielle) : les nMonths d'avant.
  const series = periodSeries(data, nMonths + 1, todayIso).slice(0, nMonths)
  if (series.length === 0) return 0
  return Math.round(series.reduce((sum, p) => sum + p.income, 0) / series.length)
}

export interface BudgetAllocation {
  /** Revenu mensuel servant de référence. */
  reference: number
  /** Vrai si la référence vient d'un montant saisi, faux si c'est la moyenne. */
  referenceIsManual: boolean
  /** Somme des budgets mensuels définis. */
  totalBudgeted: number
  /** Reste à attribuer = référence − total budgété (peut être négatif). */
  remaining: number
}

export function budgetAllocation(data: AppData, todayIso?: string): BudgetAllocation {
  const manual = data.settings.monthlyIncomeReference
  const reference = manual > 0 ? manual : averageMonthlyIncome(data, 3, todayIso)
  const totalBudgeted = alive(data.budgets).reduce((sum, b) => sum + b.monthlyAmount, 0)
  return {
    reference,
    referenceIsManual: manual > 0,
    totalBudgeted,
    remaining: reference - totalBudgeted,
  }
}

export interface UnbudgetedSlice {
  category: Category | null
  amount: number
}

export interface RealAvailability {
  /** Revenu mensuel de référence (0 s'il est inconnu). */
  reference: number
  /** Total réellement dépensé sur la période, toutes catégories confondues. */
  totalSpent: number
  /** Part dépensée dans des catégories qui ont un budget. */
  spentBudgeted: number
  /** Part dépensée hors de tout budget (invisible des jauges). */
  spentUnbudgeted: number
  /** Ce qui reste encore réservé dans les enveloppes budgétaires. */
  budgetReserved: number
  /** Revenu − dépensé − encore réservé : ce qu'il reste vraiment de libre. */
  realRemaining: number
  /** Détail des dépenses hors budget, du plus gros au plus petit. */
  unbudgeted: UnbudgetedSlice[]
}

/**
 * Vue « honnête » du mois : les dépenses faites dans des catégories sans budget
 * n'apparaissent dans aucune jauge, ce qui donne l'illusion qu'il reste plus
 * d'argent qu'en réalité. On les soustrait donc explicitement ici, au lieu de
 * réécrire les budgets de l'utilisateur.
 */
export function realAvailability(data: AppData, period: Period, todayIso?: string): RealAvailability {
  const budgetedRoots = alive(data.budgets).map((b) => b.categoryId)
  // Une catégorie est « couverte » si elle-même ou un de ses parents a un budget.
  const covered = new Set<string>()
  for (const root of budgetedRoots) {
    for (const id of categoryWithChildren(root, data.categories)) covered.add(id)
  }

  const categoryById = new Map(alive(data.categories).map((c) => [c.id, c]))
  const expenses = alive(data.transactions).filter(
    (t) => t.type === 'expense' && inPeriod(t.date, period),
  )

  let spentBudgeted = 0
  const unbudgetedMap = new Map<string | null, number>()
  for (const t of expenses) {
    if (t.categoryId && covered.has(t.categoryId)) {
      spentBudgeted += t.amount
    } else {
      const key = t.categoryId ?? null
      unbudgetedMap.set(key, (unbudgetedMap.get(key) ?? 0) + t.amount)
    }
  }

  const spentUnbudgeted = [...unbudgetedMap.values()].reduce((a, b) => a + b, 0)
  const budgetReserved = budgetStatuses(data, period, todayIso).reduce(
    (sum, s) => sum + Math.max(0, s.budget.monthlyAmount - s.spent),
    0,
  )
  const { reference } = budgetAllocation(data, todayIso)
  const totalSpent = spentBudgeted + spentUnbudgeted

  const unbudgeted: UnbudgetedSlice[] = [...unbudgetedMap.entries()]
    .map(([id, amount]) => ({ category: id ? (categoryById.get(id) ?? null) : null, amount }))
    .sort((a, b) => b.amount - a.amount)

  return {
    reference,
    totalSpent,
    spentBudgeted,
    spentUnbudgeted,
    budgetReserved,
    realRemaining: reference - totalSpent - budgetReserved,
    unbudgeted,
  }
}

export type BudgetLevel = 'ok' | 'warning' | 'over'

export interface BudgetStatus {
  budget: Budget
  category: Category
  spent: number
  /** Part de `spent` venant d'opérations générées par un abonnement ou un prêt. */
  subscriptionSpent: number
  /** 0..n — 1 = budget consommé. */
  ratio: number
  level: BudgetLevel
  /** Date estimée de dépassement (YYYY-MM-DD) si le rythme actuel s'y dirige. */
  projectedOverDate: string | null
}

export function budgetStatuses(data: AppData, period: Period, todayIso?: string): BudgetStatus[] {
  const categories = alive(data.categories)
  const byId = new Map(categories.map((c) => [c.id, c]))
  const { elapsedDays } = periodProgress(period, todayIso)
  const statuses: BudgetStatus[] = []
  for (const budget of alive(data.budgets)) {
    const category = byId.get(budget.categoryId)
    if (!category) continue
    // Les prélèvements sont devenus de vraies opérations : ils sont déjà comptés
    // dans `spentForCategory`. On isole juste leur part, à titre informatif.
    const spent = spentForCategory(budget.categoryId, data, period)
    const ids = categoryWithChildren(budget.categoryId, data.categories)
    const subscriptionSpent = alive(data.transactions)
      .filter(
        (t) =>
          t.type === 'expense' &&
          t.subscriptionId &&
          t.categoryId &&
          ids.has(t.categoryId) &&
          inPeriod(t.date, period),
      )
      .reduce((sum, t) => sum + t.amount, 0)
    const ratio = budget.monthlyAmount > 0 ? spent / budget.monthlyAmount : 0
    const level: BudgetLevel =
      ratio >= 1 ? 'over' : ratio >= data.settings.warnThreshold / 100 ? 'warning' : 'ok'

    let projectedOverDate: string | null = null
    if (level !== 'over' && spent > 0 && elapsedDays > 0) {
      const dailyRate = spent / elapsedDays
      const daysToOver = Math.ceil((budget.monthlyAmount - spent) / dailyRate)
      const overDate = new Date(period.start)
      overDate.setDate(overDate.getDate() + elapsedDays - 1 + daysToOver)
      if (overDate <= new Date(period.end)) {
        projectedOverDate = overDate.toISOString().slice(0, 10)
      }
    }
    statuses.push({ budget, category, spent, subscriptionSpent, ratio, level, projectedOverDate })
  }
  return statuses.sort((a, b) => b.ratio - a.ratio)
}

export interface Kpis {
  totalBalance: number
  periodExpense: number
  periodIncome: number
  /** Somme des budgets définis moins ce qui y a déjà été dépensé. */
  remainingBudget: number
  /** (revenus - dépenses) / revenus, en pourcentage ; null sans revenus. */
  savingsRate: number | null
}

export function computeKpis(data: AppData, period: Period, todayIso?: string): Kpis {
  const { expense, income } = periodTotals(data.transactions, period)
  const statuses = budgetStatuses(data, period, todayIso)
  const remainingBudget = statuses.reduce(
    (sum, s) => sum + Math.max(0, s.budget.monthlyAmount - s.spent),
    0,
  )
  return {
    totalBalance: totalBalance(data.accounts, data.transactions),
    periodExpense: expense,
    periodIncome: income,
    remainingBudget,
    savingsRate: income > 0 ? Math.round(((income - expense) / income) * 100) : null,
  }
}
