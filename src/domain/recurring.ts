// Génération automatique des opérations à partir des abonnements et prêts.
//
// Principe : à chaque ouverture de l'application, toute échéance arrivée (date
// passée ou du jour) qui n'a pas encore d'opération correspondante en crée une.
// C'est ensuite cette opération, comme n'importe quelle dépense saisie, qui
// alimente la consommation des budgets — d'où un suivi identique pour tout.
//
// L'opération porte l'identifiant de l'abonnement et la date de l'échéance :
// une échéance déjà matérialisée (même supprimée par l'utilisateur) n'est jamais
// recréée.

import { alive, stamp, type AppData, type Subscription, type Transaction } from './types.ts'
import { isCommitmentActive } from './subscriptions.ts'

function daysInMonth(year: number, month: number): number {
  return new Date(year, month, 0).getDate()
}

function iso(year: number, month: number, day: number): string {
  const d = Math.min(day, daysInMonth(year, month))
  return `${year}-${String(month).padStart(2, '0')}-${String(d).padStart(2, '0')}`
}

/**
 * Échéances d'un prélèvement entre `fromIso` (inclus) et `toIso` (inclus).
 * Un abonnement annuel n'a qu'une échéance par an, dans son mois ; un mensuel
 * (ou un prêt) en a une par mois, au jour indiqué.
 */
export function dueOccurrences(sub: Subscription, fromIso: string, toIso: string): string[] {
  if (fromIso > toIso) return []
  const [fy, fm] = fromIso.split('-').map(Number)
  const [ty, tm] = toIso.split('-').map(Number)
  const yearly = sub.kind === 'subscription' && sub.frequency === 'yearly'
  const out: string[] = []

  for (let index = fy * 12 + (fm - 1); index <= ty * 12 + (tm - 1); index++) {
    const year = Math.floor(index / 12)
    const month = (index % 12) + 1
    if (yearly && sub.dueMonth && month !== sub.dueMonth) continue
    const date = iso(year, month, sub.dayOfMonth)
    if (date < fromIso || date > toIso) continue
    // Un prêt s'arrête après sa dernière mensualité.
    if (sub.kind === 'loan' && sub.endDate && date > sub.endDate) continue
    out.push(date)
  }
  return out
}

/** Date de la prochaine échéance à venir (strictement après aujourd'hui). */
export function nextOccurrence(sub: Subscription, todayIso: string): string | null {
  const [y, m] = todayIso.split('-').map(Number)
  // On regarde jusqu'à 13 mois devant pour couvrir le cas annuel.
  const end = iso(y + 1, m, 28)
  const upcoming = dueOccurrences(sub, todayIso, end).filter((d) => d > todayIso)
  return upcoming[0] ?? null
}

/**
 * Opérations à créer pour rattraper toutes les échéances arrivées.
 * Les échéances antérieures à la création de l'abonnement ne sont pas
 * rétro-générées (on ne réécrit pas le passé de l'utilisateur).
 */
export function pendingSubscriptionTransactions(
  data: Pick<AppData, 'subscriptions' | 'transactions' | 'settings'>,
  todayIso: string,
): Transaction[] {
  // Toutes les échéances déjà matérialisées, tombstones inclus : on ne recrée
  // jamais une opération que l'utilisateur aurait supprimée volontairement.
  const done = new Set(
    data.transactions
      .filter((t) => t.subscriptionId && t.occurrence)
      .map((t) => `${t.subscriptionId}:${t.occurrence}`),
  )

  const created: Transaction[] = []
  for (const sub of alive(data.subscriptions)) {
    if (!sub.active) continue
    const startIso = sub.createdAt.slice(0, 10)
    for (const occurrence of dueOccurrences(sub, startIso, todayIso)) {
      // Un prêt échu ou un abonnement en pause ne génère rien après sa fin.
      if (!isCommitmentActive(sub, occurrence)) continue
      if (done.has(`${sub.id}:${occurrence}`)) continue
      created.push({
        ...stamp(),
        type: 'expense',
        amount: sub.amount,
        date: occurrence,
        accountId: sub.accountId ?? data.settings.defaultAccountId ?? '',
        toAccountId: null,
        categoryId: sub.categoryId,
        note: sub.kind === 'loan' ? 'Mensualité de prêt' : 'Prélèvement automatique',
        payee: sub.name,
        subscriptionId: sub.id,
        occurrence,
      })
    }
  }
  return created
}
