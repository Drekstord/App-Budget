// Alertes déclenchées au moment de l'ajout d'une opération : franchissement
// des seuils de budget et dépense inhabituellement élevée.

import type { AppData, Transaction } from './types.ts'
import { formatEUR, formatEURCompact } from './money.ts'
import { periodForDate, todayISO } from './periods.ts'
import { categoryWithChildren, spentForCategory } from './stats.ts'
import { alive } from './types.ts'

export interface TransactionAlert {
  id: string
  severity: 'critical' | 'warning' | 'info'
  text: string
}

type NewTransaction = Pick<Transaction, 'type' | 'amount' | 'date' | 'categoryId'>

/**
 * Calcule les alertes provoquées par une nouvelle opération, à partir des
 * données AVANT son ajout (pour détecter les franchissements de seuil).
 */
export function computeTransactionAlerts(
  dataBefore: AppData,
  transaction: NewTransaction,
  todayIso = todayISO(),
): TransactionAlert[] {
  const alerts: TransactionAlert[] = []
  if (transaction.type !== 'expense') return alerts
  const { settings } = dataBefore

  // 1. Grosse dépense au-delà du seuil configuré.
  if (settings.largeExpenseAlert > 0 && transaction.amount >= settings.largeExpenseAlert) {
    alerts.push({
      id: 'large-expense',
      severity: 'info',
      text: `Grosse dépense enregistrée : ${formatEUR(transaction.amount)} (seuil d’alerte : ${formatEURCompact(settings.largeExpenseAlert)}).`,
    })
  }

  // 2. Franchissement de seuil sur le budget de la catégorie (ou d'un parent).
  if (transaction.categoryId) {
    const period = periodForDate(transaction.date, settings.monthStartDay)
    // Seules les opérations de la période courante déclenchent une alerte.
    const currentPeriod = periodForDate(todayIso, settings.monthStartDay)
    if (period.key !== currentPeriod.key) return alerts

    for (const budget of alive(dataBefore.budgets)) {
      const covered = categoryWithChildren(budget.categoryId, dataBefore.categories)
      if (!covered.has(transaction.categoryId)) continue
      const category = alive(dataBefore.categories).find((c) => c.id === budget.categoryId)
      if (!category || budget.monthlyAmount <= 0) continue

      const before = spentForCategory(budget.categoryId, dataBefore, period)
      const after = before + transaction.amount
      const warnAt = (budget.monthlyAmount * settings.warnThreshold) / 100

      if (before < budget.monthlyAmount && after >= budget.monthlyAmount) {
        alerts.push({
          id: `budget-over-${budget.id}`,
          severity: 'critical',
          text: `Plafond atteint : budget « ${category.name} » dépassé (${formatEUR(after)} sur ${formatEURCompact(budget.monthlyAmount)}).`,
        })
      } else if (before < warnAt && after >= warnAt) {
        alerts.push({
          id: `budget-warn-${budget.id}`,
          severity: 'warning',
          text: `Budget « ${category.name} » consommé à ${Math.round((after / budget.monthlyAmount) * 100)} % (${formatEUR(after)} sur ${formatEURCompact(budget.monthlyAmount)}).`,
        })
      }
    }
  }

  return alerts
}
