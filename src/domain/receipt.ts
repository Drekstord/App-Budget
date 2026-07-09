// Analyse du texte OCR d'un ticket de caisse : montant total, date, enseigne
// et suggestion de catégorie. Heuristiques volontairement prudentes — le
// résultat est toujours soumis à confirmation avant ajout.

export interface ParsedReceipt {
  amountCents: number | null
  /** YYYY-MM-DD */
  date: string | null
  merchant: string | null
  /** Nom d'une catégorie par défaut à suggérer (ex. "Alimentation"). */
  categoryHint: string | null
}

/** Minuscules sans accents, pour des comparaisons robustes au bruit OCR. */
function normalize(s: string): string {
  return s
    .toLowerCase()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
}

interface Brand {
  match: string
  display: string
  category: string
}

const BRANDS: Brand[] = [
  // Alimentation
  { match: 'carrefour', display: 'Carrefour', category: 'Alimentation' },
  { match: 'leclerc', display: 'E.Leclerc', category: 'Alimentation' },
  { match: 'lidl', display: 'Lidl', category: 'Alimentation' },
  { match: 'auchan', display: 'Auchan', category: 'Alimentation' },
  { match: 'intermarche', display: 'Intermarché', category: 'Alimentation' },
  { match: 'monoprix', display: 'Monoprix', category: 'Alimentation' },
  { match: 'franprix', display: 'Franprix', category: 'Alimentation' },
  { match: 'casino', display: 'Casino', category: 'Alimentation' },
  { match: 'aldi', display: 'Aldi', category: 'Alimentation' },
  { match: 'super u', display: 'Super U', category: 'Alimentation' },
  { match: 'hyper u', display: 'Hyper U', category: 'Alimentation' },
  { match: 'picard', display: 'Picard', category: 'Alimentation' },
  { match: 'biocoop', display: 'Biocoop', category: 'Alimentation' },
  { match: 'grand frais', display: 'Grand Frais', category: 'Alimentation' },
  { match: 'boulangerie', display: 'Boulangerie', category: 'Alimentation' },
  // Restaurants
  { match: 'mcdonald', display: 'McDonald’s', category: 'Restaurants' },
  { match: 'burger king', display: 'Burger King', category: 'Restaurants' },
  { match: 'kfc', display: 'KFC', category: 'Restaurants' },
  { match: 'subway', display: 'Subway', category: 'Restaurants' },
  { match: 'domino', display: 'Domino’s', category: 'Restaurants' },
  { match: 'pizza', display: 'Pizzeria', category: 'Restaurants' },
  { match: 'restaurant', display: 'Restaurant', category: 'Restaurants' },
  { match: 'brasserie', display: 'Brasserie', category: 'Restaurants' },
  // Transport
  { match: 'sncf', display: 'SNCF', category: 'Transport' },
  { match: 'ratp', display: 'RATP', category: 'Transport' },
  { match: 'totalenergies', display: 'TotalEnergies', category: 'Transport' },
  { match: 'esso', display: 'Esso', category: 'Transport' },
  { match: 'shell', display: 'Shell', category: 'Transport' },
  { match: 'station service', display: 'Station-service', category: 'Transport' },
  { match: 'autoroute', display: 'Péage autoroute', category: 'Transport' },
  { match: 'parking', display: 'Parking', category: 'Transport' },
  { match: 'uber', display: 'Uber', category: 'Transport' },
  // Santé
  { match: 'pharmacie', display: 'Pharmacie', category: 'Santé' },
  { match: 'parapharmacie', display: 'Parapharmacie', category: 'Santé' },
  { match: 'laboratoire', display: 'Laboratoire', category: 'Santé' },
  // Loisirs
  { match: 'cinema', display: 'Cinéma', category: 'Loisirs' },
  { match: 'pathe', display: 'Pathé', category: 'Loisirs' },
  { match: 'gaumont', display: 'Gaumont', category: 'Loisirs' },
  { match: 'ugc', display: 'UGC', category: 'Loisirs' },
  { match: 'decathlon', display: 'Decathlon', category: 'Loisirs' },
  { match: 'fnac', display: 'Fnac', category: 'Loisirs' },
  // Vêtements
  { match: 'kiabi', display: 'Kiabi', category: 'Vêtements' },
  { match: 'zara', display: 'Zara', category: 'Vêtements' },
  { match: 'h&m', display: 'H&M', category: 'Vêtements' },
  { match: 'primark', display: 'Primark', category: 'Vêtements' },
  { match: 'celio', display: 'Celio', category: 'Vêtements' },
]

/** Mots-clés forts : la ligne porte très probablement le total à payer. */
const PRIMARY_KEYWORDS = /\b(total|montant|a\s*payer|net\s*a\s*payer|ttc|somme\s*due)\b/
/** Mots-clés de paiement : total probable, mais peut être un billet tendu. */
const PAYMENT_KEYWORDS = /\b(cb|carte\s*(bancaire|bleue)|especes|cheque|paiement)\b/
/** Lignes à ignorer : rendu de monnaie, remises… */
const EXCLUDED_LINES = /\b(rendu|monnaie|rendre|remise|reduction|economie|cagnotte)\b/

/** Lignes d'en-tête sans intérêt pour deviner l'enseigne. */
const HEADER_NOISE =
  /\b(ticket|caisse|recu|facture|bienvenue|merci|tel|tva|siret|siren|rcs|www|http|date|heure|client|vendeur|magasin\s*n)\b|^\W*$|^\d[\d\s\/.:-]*$/

const MONTHS: Record<string, number> = {
  janvier: 1, fevrier: 2, mars: 3, avril: 4, mai: 5, juin: 6,
  juillet: 7, aout: 8, septembre: 9, octobre: 10, novembre: 11, decembre: 12,
  janv: 1, fevr: 2, avr: 4, juil: 7, sept: 9, oct: 10, nov: 11, dec: 12,
}

function toISO(year: number, month: number, day: number): string | null {
  if (year < 100) year += 2000
  if (year < 2000 || year > 2100 || month < 1 || month > 12 || day < 1 || day > 31) return null
  const d = new Date(year, month - 1, day)
  if (d.getMonth() !== month - 1 || d.getDate() !== day) return null
  return `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`
}

function findDate(normalized: string): string | null {
  // Formats numériques : 03/07/2026, 03-07-26, 03.07.2026
  const numeric = normalized.matchAll(/\b(\d{1,2})[/.\-](\d{1,2})[/.\-](\d{2}(?:\d{2})?)\b/g)
  for (const m of numeric) {
    const iso = toISO(Number(m[3]), Number(m[2]), Number(m[1]))
    if (iso) return iso
  }
  // Formats textuels : 3 juillet 2026
  const textual = normalized.matchAll(/\b(\d{1,2})\s+([a-z]{3,9})\.?\s+(\d{2}(?:\d{2})?)\b/g)
  for (const m of textual) {
    const month = MONTHS[m[2]]
    if (!month) continue
    const iso = toISO(Number(m[3]), month, Number(m[1]))
    if (iso) return iso
  }
  return null
}

interface FoundAmount {
  cents: number
  lineIdx: number
}

function findAmounts(lines: string[]): FoundAmount[] {
  const found: FoundAmount[] = []
  lines.forEach((line, lineIdx) => {
    // 12,50 / 12.50 / 1 234,56 — deux décimales obligatoires (écarte quantités et dates)
    for (const m of line.matchAll(/(\d{1,3}(?:[\s.]\d{3})*|\d{1,6})\s*[,.]\s*(\d{2})(?!\d)/g)) {
      const whole = Number(m[1].replace(/[\s.]/g, ''))
      const cents = whole * 100 + Number(m[2])
      if (cents > 0 && cents < 100_000_00) found.push({ cents, lineIdx })
    }
  })
  return found
}

function findMerchant(lines: string[]): { merchant: string | null; categoryHint: string | null } {
  const wholeText = normalize(lines.join('\n'))
  for (const brand of BRANDS) {
    if (wholeText.includes(brand.match)) {
      return { merchant: brand.display, categoryHint: brand.category }
    }
  }
  // À défaut : première ligne « propre » du haut du ticket.
  for (const line of lines.slice(0, 6)) {
    const trimmed = line.trim()
    const norm = normalize(trimmed)
    const letters = norm.replace(/[^a-z]/g, '')
    if (letters.length >= 3 && !HEADER_NOISE.test(norm)) {
      return { merchant: trimmed.replace(/\s{2,}/g, ' ').slice(0, 40), categoryHint: null }
    }
  }
  return { merchant: null, categoryHint: null }
}

export function parseReceipt(text: string): ParsedReceipt {
  const lines = text
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l.length > 0)
  const normalizedLines = lines.map(normalize)

  const amounts = findAmounts(lines).filter((a) => !EXCLUDED_LINES.test(normalizedLines[a.lineIdx]))
  let amountCents: number | null = null
  // Priorité : lignes « total/à payer » > lignes de paiement > max du ticket.
  const collectNear = (keywords: RegExp): FoundAmount[] => {
    const idx = new Set<number>()
    normalizedLines.forEach((line, i) => {
      if (!keywords.test(line)) return
      idx.add(i)
      // Total parfois renvoyé à la ligne suivante — seulement si la ligne
      // du mot-clé ne porte aucun montant elle-même.
      if (!amounts.some((a) => a.lineIdx === i)) idx.add(i + 1)
    })
    return amounts.filter((a) => idx.has(a.lineIdx))
  }
  const primary = collectNear(PRIMARY_KEYWORDS)
  const payment = collectNear(PAYMENT_KEYWORDS)
  const pool = primary.length > 0 ? primary : payment.length > 0 ? payment : amounts
  if (pool.length > 0) {
    amountCents = Math.max(...pool.map((a) => a.cents))
  }

  const date = findDate(normalize(text))
  const { merchant, categoryHint } = findMerchant(lines)

  return { amountCents, date, merchant, categoryHint }
}
