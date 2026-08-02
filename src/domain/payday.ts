// Détection du jour de paie : le mois budgétaire de l'utilisateur commence
// quand il touche son salaire, pas le 1er du mois civil. Plutôt que de lui
// faire chercher ce jour, on le déduit de ses revenus déjà saisis.

import { alive, type AppData, type Transaction } from './types.ts'
import { daysInMonth, todayISO } from './periods.ts'

export interface PaydaySuggestion {
  /** Jour de début proposé (31 = dernier jour du mois). */
  day: number
  /** Nombre de mois sur lesquels un revenu principal a été trouvé. */
  monthsAnalysed: number
  /** De quoi il s'agit : marchand, sinon catégorie, sinon « ton revenu ». */
  label: string
  /** Montant moyen du revenu principal, pour rendre la proposition vérifiable. */
  averageAmount: number
  /** Vrai si le versement tombe systématiquement en fin de mois. */
  endOfMonth: boolean
}

function monthsBefore(iso: string, n: number): string {
  const [y, m, d] = iso.split('-').map(Number)
  const date = new Date(y, m - 1 - n, d)
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(
    date.getDate(),
  ).padStart(2, '0')}`
}

/** Jour du mois d'une date ISO. */
function dayOf(iso: string): number {
  return Number(iso.slice(8, 10))
}

/** Le versement tombe-t-il dans les deux derniers jours de son mois ? */
function fallsAtMonthEnd(iso: string): boolean {
  const [y, m] = iso.split('-').map(Number)
  return dayOf(iso) >= daysInMonth(y, m - 1) - 1
}

/**
 * Propose un jour de début de mois budgétaire d'après les revenus enregistrés :
 * on retient le plus gros revenu de chaque mois civil (le salaire domine les
 * revenus d'appoint) sur les douze derniers mois, puis le jour qui revient le
 * plus souvent. Retourne null tant qu'il n'y a pas au moins deux mois d'historique.
 */
export function suggestPayday(data: AppData, todayIso = todayISO()): PaydaySuggestion | null {
  const cutoff = monthsBefore(todayIso, 12)
  const byMonth = new Map<string, Transaction>()
  for (const t of alive(data.transactions)) {
    if (t.type !== 'income' || t.date < cutoff || t.date > todayIso) continue
    const key = t.date.slice(0, 7)
    const current = byMonth.get(key)
    if (!current || t.amount > current.amount) byMonth.set(key, t)
  }

  const picks = [...byMonth.values()].sort((a, b) => a.date.localeCompare(b.date))
  if (picks.length < 2) return null

  // Un salaire « fin de mois » ne tombe pas au même numéro selon les mois :
  // dans ce cas on propose « dernier jour » plutôt qu'un jour fixe.
  const endOfMonth = picks.every((t) => fallsAtMonthEnd(t.date))

  let day: number
  if (endOfMonth) {
    day = 31
  } else {
    const counts = new Map<number, number>()
    for (const t of picks) {
      const d = dayOf(t.date)
      counts.set(d, (counts.get(d) ?? 0) + 1)
    }
    const mostRecent = dayOf(picks[picks.length - 1].date)
    // Égalité de fréquence : on suit le versement le plus récent.
    day = [...counts.entries()].sort(
      (a, b) => b[1] - a[1] || (a[0] === mostRecent ? -1 : b[0] === mostRecent ? 1 : 0),
    )[0][0]
  }

  const latest = picks[picks.length - 1]
  const category = latest.categoryId
    ? alive(data.categories).find((c) => c.id === latest.categoryId)
    : undefined
  const label = latest.payee.trim() || category?.name || 'ton revenu'
  const averageAmount = Math.round(picks.reduce((s, t) => s + t.amount, 0) / picks.length)

  return { day, monthsAnalysed: picks.length, label, averageAmount, endOfMonth }
}
