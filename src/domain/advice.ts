// Moteur de conseils : règles simples calculées localement, aucune donnée
// n'est envoyée à l'extérieur.

import type { AppData } from './types.ts'
import { formatEURCompact } from './money.ts'
import { periodForDate, periodProgress, shiftPeriod, todayISO } from './periods.ts'
import { budgetStatuses, computeKpis, expensesByRootCategory, spentForCategory } from './stats.ts'

export type AdviceSeverity = 'critical' | 'warning' | 'info' | 'good'

export interface Advice {
  id: string
  severity: AdviceSeverity
  text: string
}

const dayLabel = new Intl.DateTimeFormat('fr-FR', { day: 'numeric', month: 'long' })

export function computeAdvice(data: AppData, todayIso = todayISO()): Advice[] {
  const advice: Advice[] = []
  const startDay = data.settings.monthStartDay
  const period = periodForDate(todayIso, startDay)
  const statuses = budgetStatuses(data, period, todayIso)
  const kpis = computeKpis(data, period, todayIso)

  // 1. Budgets dépassés ou en passe de l'être.
  for (const s of statuses) {
    if (s.level === 'over') {
      advice.push({
        id: `over-${s.budget.id}`,
        severity: 'critical',
        text: `Budget « ${s.category.name} » dépassé : ${formatEURCompact(s.spent)} dépensés sur ${formatEURCompact(s.budget.monthlyAmount)}.`,
      })
    } else if (s.projectedOverDate) {
      advice.push({
        id: `proj-${s.budget.id}`,
        severity: 'warning',
        text: `À ce rythme, ton budget « ${s.category.name} » sera dépassé vers le ${dayLabel.format(new Date(s.projectedOverDate))}.`,
      })
    } else if (s.level === 'warning') {
      advice.push({
        id: `warn-${s.budget.id}`,
        severity: 'warning',
        text: `Budget « ${s.category.name} » consommé à ${Math.round(s.ratio * 100)} %. Garde un œil dessus.`,
      })
    }
  }

  // 2. Catégories en dérive par rapport à la moyenne des 3 périodes précédentes,
  //    au pro-rata des jours écoulés.
  const { totalDays, elapsedDays } = periodProgress(period, todayIso)
  if (elapsedDays >= 7) {
    const slices = expensesByRootCategory(data, period)
    for (const slice of slices.slice(0, 6)) {
      let historyTotal = 0
      let historyCount = 0
      for (let i = 1; i <= 3; i++) {
        const past = shiftPeriod(period, -i, startDay)
        const spent = spentForCategory(slice.category.id, data, past)
        if (spent > 0) {
          historyTotal += spent
          historyCount++
        }
      }
      if (historyCount < 2) continue
      const average = historyTotal / historyCount
      const projected = (slice.amount / elapsedDays) * totalDays
      const driftPct = Math.round(((projected - average) / average) * 100)
      if (driftPct >= 30 && projected - average >= 2000) {
        advice.push({
          id: `drift-${slice.category.id}`,
          severity: 'warning',
          text: `Tes dépenses « ${slice.category.name} » filent vers ${driftPct} % au-dessus de ta moyenne habituelle (${formatEURCompact(Math.round(average))}/mois).`,
        })
      }
    }
  }

  // 3. Taux d'épargne.
  if (kpis.savingsRate !== null) {
    if (kpis.savingsRate < 0) {
      advice.push({
        id: 'negative-savings',
        severity: 'critical',
        text: `Ce mois-ci, tu dépenses plus que tes revenus (${formatEURCompact(kpis.periodExpense - kpis.periodIncome)} de plus).`,
      })
    } else if (kpis.savingsRate >= 20 && elapsedDays >= totalDays * 0.8) {
      advice.push({
        id: 'good-savings',
        severity: 'good',
        text: `Beau taux d'épargne : ${kpis.savingsRate} % de tes revenus mis de côté ce mois-ci. 👏`,
      })
    }
  }

  // 4. Suggestion : un budget sur la première catégorie de dépense sans budget.
  const budgeted = new Set(statuses.map((s) => s.category.id))
  const topUnbudgeted = expensesByRootCategory(data, period).find(
    (s) => !budgeted.has(s.category.id),
  )
  if (topUnbudgeted && topUnbudgeted.amount >= 5000) {
    advice.push({
      id: `suggest-${topUnbudgeted.category.id}`,
      severity: 'info',
      text: `« ${topUnbudgeted.category.name} » est un gros poste (${formatEURCompact(topUnbudgeted.amount)} ce mois-ci) sans budget. Fixe-lui une limite pour mieux le piloter.`,
    })
  }

  const order: Record<AdviceSeverity, number> = { critical: 0, warning: 1, info: 2, good: 3 }
  return advice.sort((a, b) => order[a.severity] - order[b.severity])
}
