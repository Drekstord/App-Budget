// Mois budgétaire : commence le jour choisi par l'utilisateur (ex. le 28,
// jour de paie) et se termine la veille du début suivant.

export interface Period {
  /** Identifiant stable, ex. "2026-07" (année-mois du jour de début). */
  key: string
  /** Premier jour inclus, YYYY-MM-DD. */
  start: string
  /** Dernier jour inclus, YYYY-MM-DD. */
  end: string
  /** Libellé lisible, ex. "juillet 2026" ou "28 juin – 27 juil.". */
  label: string
  /**
   * Libellé court pour les axes de graphiques : le mois qui couvre la majeure
   * partie de la période. Un mois budgétaire du 28 juin au 27 juillet se lit
   * « juil. », pas « juin ».
   */
  shortLabel: string
}

function toISODate(d: Date): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

export function todayISO(): string {
  return toISODate(new Date())
}

function fromISODate(iso: string): Date {
  const [y, m, d] = iso.split('-').map(Number)
  return new Date(y, m - 1, d)
}

const monthLabel = new Intl.DateTimeFormat('fr-FR', { month: 'long', year: 'numeric' })
const dayMonthLabel = new Intl.DateTimeFormat('fr-FR', { day: 'numeric', month: 'short' })
const monthOnlyLabel = new Intl.DateTimeFormat('fr-FR', { month: 'short' })

/** Nombre de jours du mois (month peut déborder : -1 = décembre précédent). */
export function daysInMonth(year: number, month: number): number {
  return new Date(year, month + 1, 0).getDate()
}

/**
 * Jour de début réellement applicable à un mois donné. Un jour de paie tardif
 * est ramené au dernier jour du mois quand celui-ci est plus court : le 31
 * signifie donc « dernier jour du mois » (28, 29, 30 ou 31 selon le mois).
 */
export function effectiveStartDay(year: number, month: number, startDay: number): number {
  const clamped = Math.min(31, Math.max(1, Math.round(startDay) || 1))
  return Math.min(clamped, daysInMonth(year, month))
}

function buildPeriod(year: number, month: number, startDay: number): Period {
  const start = new Date(year, month, effectiveStartDay(year, month, startDay))
  const nextStart = new Date(year, month + 1, effectiveStartDay(year, month + 1, startDay))
  const end = new Date(nextStart)
  end.setDate(end.getDate() - 1)
  const key = `${start.getFullYear()}-${String(start.getMonth() + 1).padStart(2, '0')}`
  const label =
    startDay === 1
      ? monthLabel.format(start)
      : `${dayMonthLabel.format(start)} – ${dayMonthLabel.format(end)} ${end.getFullYear()}`
  // Le mois du milieu de période est celui qui la représente le mieux.
  const midpoint = new Date((start.getTime() + end.getTime()) / 2)
  return {
    key,
    start: toISODate(start),
    end: toISODate(end),
    label,
    shortLabel: monthOnlyLabel.format(midpoint),
  }
}

/** Période budgétaire contenant la date donnée. */
export function periodForDate(dateISO: string, startDay: number): Period {
  const d = fromISODate(dateISO)
  const thisMonthStart = effectiveStartDay(d.getFullYear(), d.getMonth(), startDay)
  const startMonth = d.getDate() >= thisMonthStart ? d.getMonth() : d.getMonth() - 1
  return buildPeriod(d.getFullYear(), startMonth, startDay)
}

/** Période décalée de n mois (n négatif = vers le passé). */
export function shiftPeriod(period: Period, n: number, startDay: number): Period {
  const start = fromISODate(period.start)
  return buildPeriod(start.getFullYear(), start.getMonth() + n, startDay)
}

/** Les n dernières périodes, de la plus ancienne à la courante. */
export function lastPeriods(n: number, startDay: number, todayIso = todayISO()): Period[] {
  const current = periodForDate(todayIso, startDay)
  const result: Period[] = []
  for (let i = n - 1; i >= 0; i--) {
    result.push(shiftPeriod(current, -i, startDay))
  }
  return result
}

export function inPeriod(dateISO: string, period: Period): boolean {
  return dateISO >= period.start && dateISO <= period.end
}

/** Nombre de jours de la période et jours écoulés (bornés) à la date donnée. */
export function periodProgress(
  period: Period,
  todayIso = todayISO(),
): { totalDays: number; elapsedDays: number } {
  const msPerDay = 86_400_000
  const start = fromISODate(period.start).getTime()
  const end = fromISODate(period.end).getTime()
  const today = fromISODate(todayIso).getTime()
  const totalDays = Math.round((end - start) / msPerDay) + 1
  const elapsedDays = Math.min(totalDays, Math.max(0, Math.round((today - start) / msPerDay) + 1))
  return { totalDays, elapsedDays }
}
