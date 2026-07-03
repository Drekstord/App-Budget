// Entités du domaine. Toutes portent un UUID, des horodatages et un marqueur
// de suppression logique (tombstone) pour permettre une future synchronisation
// par fusion avec un serveur privé (cf. EXIGENCES.md RNF-6).

export interface BaseEntity {
  id: string
  createdAt: string
  updatedAt: string
  deletedAt: string | null
}

export type AccountType = 'checking' | 'savings' | 'cash' | 'card' | 'other'

export interface Account extends BaseEntity {
  name: string
  type: AccountType
  /** Solde initial en centimes (peut être négatif). */
  initialBalance: number
  icon: string
  archived: boolean
}

export type CategoryKind = 'expense' | 'income'

export interface Category extends BaseEntity {
  name: string
  kind: CategoryKind
  parentId: string | null
  icon: string
  /** Emplacement 1 à 8 dans la palette catégorielle. */
  colorSlot: number
}

export type TransactionType = 'expense' | 'income' | 'transfer'

export interface Transaction extends BaseEntity {
  type: TransactionType
  /** Montant en centimes, toujours positif ; le type porte le sens. */
  amount: number
  /** Date comptable au format YYYY-MM-DD. */
  date: string
  accountId: string
  /** Compte destinataire pour les virements. */
  toAccountId: string | null
  categoryId: string | null
  note: string
  payee: string
}

export interface Budget extends BaseEntity {
  categoryId: string
  /** Montant mensuel en centimes. */
  monthlyAmount: number
}

export type ThemePreference = 'auto' | 'light' | 'dark'

export interface Settings {
  theme: ThemePreference
  /** Jour de début du mois budgétaire (1 à 28). */
  monthStartDay: number
  /** Verrouillage automatique après N minutes d'inactivité (0 = désactivé). */
  lockDelayMinutes: number
  /** Seuil d'avertissement budget, en pourcentage (ex. 80). */
  warnThreshold: number
  defaultAccountId: string | null
}

export interface AppData {
  accounts: Account[]
  categories: Category[]
  transactions: Transaction[]
  budgets: Budget[]
  settings: Settings
}

export const DEFAULT_SETTINGS: Settings = {
  theme: 'auto',
  monthStartDay: 1,
  lockDelayMinutes: 5,
  warnThreshold: 80,
  defaultAccountId: null,
}

export function newId(): string {
  return crypto.randomUUID()
}

export function nowISO(): string {
  return new Date().toISOString()
}

export function stamp(): Pick<BaseEntity, 'id' | 'createdAt' | 'updatedAt' | 'deletedAt'> {
  const now = nowISO()
  return { id: newId(), createdAt: now, updatedAt: now, deletedAt: null }
}

/** Filtre les tombstones : ce que l'UI doit afficher. */
export function alive<T extends BaseEntity>(items: T[]): T[] {
  return items.filter((i) => !i.deletedAt)
}
