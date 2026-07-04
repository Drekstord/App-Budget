// Montants manipulés en centimes (entiers) pour éviter les erreurs de flottants.

const eurFormatter = new Intl.NumberFormat('fr-FR', {
  style: 'currency',
  currency: 'EUR',
})

const eurCompactFormatter = new Intl.NumberFormat('fr-FR', {
  style: 'currency',
  currency: 'EUR',
  maximumFractionDigits: 0,
})

export function formatEUR(cents: number): string {
  return eurFormatter.format(cents / 100)
}

/** Sans décimales, pour les axes de graphiques et les jauges. */
export function formatEURCompact(cents: number): string {
  return eurCompactFormatter.format(cents / 100)
}

/**
 * Interprète une saisie utilisateur française ("12,50", "1 234,56", "12.5")
 * en centimes. Retourne null si la saisie n'est pas un montant valide.
 */
export function parseAmountToCents(input: string): number | null {
  const cleaned = input
    .replace(/\s| | /g, '')
    .replace(/€/g, '')
    .replace(',', '.')
  if (!/^-?\d+(\.\d{1,2})?$/.test(cleaned)) return null
  const cents = Math.round(parseFloat(cleaned) * 100)
  return Number.isSafeInteger(cents) ? cents : null
}

export function centsToInput(cents: number): string {
  return (cents / 100).toFixed(2).replace('.', ',')
}
