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
const shortLabel = new Intl.DateTimeFormat('fr-FR', { day: 'numeric', month: 'short' })

function buildPeriod(startDate: Date, startDay: number): Period {
  const start = new Date(startDate)
  const nextStart = new Date(start.getFullYear(), start.getMonth() + 1, startDay)
  const end = new Date(nextStart)
  end.setDate(end.getDate() - 1)
  const key = `${start.getFullYear()}-${String(start.getMonth() + 1).padStart(2, '0')}`
  const label =
    startDay === 1
      ? monthLabel.format(start)
      : `${shortLabel.format(start)} – ${shortLabel.format(end)} ${end.getFullYear()}`
  return { key, start: toISODate(start), end: toISODate(end), label }
}

/** Période budgétaire contenant la date donnée. */
export function periodForDate(dateISO: string, startDay: number): Period {
  const d = fromISODate(dateISO)
  const startMonth = d.getDate() >= startDay ? d.getMonth() : d.getMonth() - 1
  return buildPeriod(new Date(d.getFullYear(), startMonth, startDay), startDay)
}

/** Période décalée de n mois (n négatif = vers le passé). */
export function shiftPeriod(period: Period, n: number, startDay: number): Period {
  const start = fromISODate(period.start)
  return buildPeriod(new Date(start.getFullYear(), start.getMonth() + n, startDay), startDay)
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
