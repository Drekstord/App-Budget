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

// --- Plans de financement (amortissement d'une grosse dépense) ---------------

/** Règle de mobilisation d'un compte pour un plan. */
export interface FundingAccountRule {
  accountId: string
  /** Ordre de ponction : 0 = servir en premier. */
  priority: number
  /** Montant à préserver sur ce compte, en centimes (0 = aucun). */
  keepMin: number
  /** Ne jamais ponctionner ce compte (protection totale). */
  excluded: boolean
}

export type FundingFlowKind = 'fixed' | 'variable'
export type FundingRecurrence = 'once' | 'monthly'

/** Une entrée ou une sortie d'argent prévue dans un plan. */
export interface FundingFlow {
  id: string
  label: string
  /** Montant en centimes, toujours positif. */
  amount: number
  /** Première (ou unique) occurrence, YYYY-MM-DD. */
  date: string
  recurrence: FundingRecurrence
  /** Pour un revenu : fixe (garanti) ou variable (bonus). Ignoré pour les dépenses. */
  kind: FundingFlowKind
}

export interface FundingPlan extends BaseEntity {
  name: string
  /** La grosse dépense à financer, en centimes. */
  targetAmount: number
  targetLabel: string
  /** Échéance de la dépense, YYYY-MM-DD. */
  targetDate: string
  accountRules: FundingAccountRule[]
  incomes: FundingFlow[]
  expenseEvents: FundingFlow[]
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
  /** Alerte « grosse dépense » au-delà de ce montant en centimes (0 = désactivée). */
  largeExpenseAlert: number
  /** Relayer les alertes en notifications système (si permission accordée). */
  systemNotifications: boolean
  defaultAccountId: string | null
}

export interface AppData {
  accounts: Account[]
  categories: Category[]
  transactions: Transaction[]
  budgets: Budget[]
  fundingPlans: FundingPlan[]
  settings: Settings
}

export const DEFAULT_SETTINGS: Settings = {
  theme: 'auto',
  monthStartDay: 1,
  lockDelayMinutes: 5,
  warnThreshold: 80,
  largeExpenseAlert: 10000,
  systemNotifications: false,
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
