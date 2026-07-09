// Couche d'accès aux données : unique point de contact entre l'application et
// le stockage chiffré. Pour brancher un jour une synchronisation serveur, on
// remplace ou décore cette classe sans toucher au reste de l'application.

import type { BudgetDB } from './db.ts'
import { decryptJson, deriveKey, encryptJson, randomBytes } from './crypto.ts'
import {
  DEFAULT_SETTINGS,
  type Account,
  type AppData,
  type BaseEntity,
  type Budget,
  type Category,
  type Settings,
  type Transaction,
} from '../domain/types.ts'

export type EntityTableName = 'accounts' | 'categories' | 'transactions' | 'budgets'

const META_SALT = 'salt'
const META_CHECK = 'check'
const META_SESSION = 'session'
const SETTINGS_ID = 'settings:singleton'

// --- Session de déverrouillage -----------------------------------------------
// La clé AES (non extractible : impossible d'en lire les octets) est conservée
// dans IndexedDB avec une date d'expiration, pour ne pas redemander le PIN à
// chaque rechargement de la page. Le PIN reste requis après le délai
// d'inactivité choisi, ou après un verrouillage manuel.

interface SessionValue {
  key: CryptoKey
  /** Timestamp ms d'expiration ; null = pas d'expiration (délai « Jamais »). */
  expiresAt: number | null
}

function sessionExpiry(lockDelayMinutes: number): number | null {
  return lockDelayMinutes > 0 ? Date.now() + lockDelayMinutes * 60_000 : null
}

/** Certains environnements ne savent pas cloner une CryptoKey : on dégrade sans casser. */
export async function saveSession(
  db: BudgetDB,
  key: CryptoKey,
  lockDelayMinutes: number,
): Promise<void> {
  try {
    const value: SessionValue = { key, expiresAt: sessionExpiry(lockDelayMinutes) }
    await db.meta.put({ key: META_SESSION, value })
  } catch {
    // Sans session persistée, le PIN sera simplement redemandé au rechargement.
  }
}

export async function loadSession(db: BudgetDB): Promise<CryptoKey | null> {
  try {
    const record = await db.meta.get(META_SESSION)
    if (!record) return null
    const { key, expiresAt } = record.value as SessionValue
    if (expiresAt !== null && Date.now() > expiresAt) {
      await db.meta.delete(META_SESSION)
      return null
    }
    return key
  } catch {
    return null
  }
}

/** Prolonge la session après une activité de l'utilisateur. */
export async function touchSession(db: BudgetDB, lockDelayMinutes: number): Promise<void> {
  try {
    const record = await db.meta.get(META_SESSION)
    if (!record) return
    const { key } = record.value as SessionValue
    await db.meta.put({
      key: META_SESSION,
      value: { key, expiresAt: sessionExpiry(lockDelayMinutes) } satisfies SessionValue,
    })
  } catch {
    // Voir saveSession.
  }
}

export async function clearSession(db: BudgetDB): Promise<void> {
  try {
    await db.meta.delete(META_SESSION)
  } catch {
    // Voir saveSession.
  }
}

export async function isInitialized(db: BudgetDB): Promise<boolean> {
  return (await db.meta.get(META_SALT)) !== undefined
}

/** Première utilisation : crée le sel et la valeur de contrôle du PIN. */
export async function setupVault(db: BudgetDB, pin: string): Promise<CryptoKey> {
  const salt = randomBytes(16)
  const key = await deriveKey(pin, salt)
  const check = await encryptJson(key, 'app-budget-check')
  await db.meta.bulkPut([
    { key: META_SALT, value: salt },
    { key: META_CHECK, value: check },
  ])
  return key
}

/**
 * Déverrouillage : dérive la clé et vérifie le PIN en déchiffrant la valeur de
 * contrôle (le tag d'authentification GCM échoue si le PIN est faux).
 */
export async function unlockVault(db: BudgetDB, pin: string): Promise<CryptoKey | null> {
  const saltRec = await db.meta.get(META_SALT)
  const checkRec = await db.meta.get(META_CHECK)
  if (!saltRec || !checkRec) return null
  const key = await deriveKey(pin, saltRec.value as Uint8Array)
  try {
    const check = checkRec.value as { iv: Uint8Array; data: ArrayBuffer }
    await decryptJson(key, check)
    return key
  } catch {
    return null
  }
}

export class EncryptedRepository {
  constructor(
    private db: BudgetDB,
    readonly key: CryptoKey,
  ) {}

  async loadAll(): Promise<AppData> {
    const records = await this.db.vault.toArray()
    const data: AppData = {
      accounts: [],
      categories: [],
      transactions: [],
      budgets: [],
      settings: { ...DEFAULT_SETTINGS },
    }
    for (const record of records) {
      const value = await decryptJson<unknown>(this.key, record)
      switch (record.table) {
        case 'accounts':
          data.accounts.push(value as Account)
          break
        case 'categories':
          data.categories.push(value as Category)
          break
        case 'transactions':
          data.transactions.push(value as Transaction)
          break
        case 'budgets':
          data.budgets.push(value as Budget)
          break
        case 'settings':
          data.settings = { ...DEFAULT_SETTINGS, ...(value as Settings) }
          break
      }
    }
    return data
  }

  async put(table: EntityTableName, entity: BaseEntity): Promise<void> {
    const { iv, data } = await encryptJson(this.key, entity)
    await this.db.vault.put({ id: `${table}:${entity.id}`, table, iv, data })
  }

  async putSettings(settings: Settings): Promise<void> {
    const { iv, data } = await encryptJson(this.key, settings)
    await this.db.vault.put({ id: SETTINGS_ID, table: 'settings', iv, data })
  }

  /** Restauration complète (import de sauvegarde) : remplace tout le contenu. */
  async replaceAll(data: AppData): Promise<void> {
    await this.db.vault.clear()
    const tables: EntityTableName[] = ['accounts', 'categories', 'transactions', 'budgets']
    for (const table of tables) {
      for (const entity of data[table]) {
        await this.put(table, entity)
      }
    }
    await this.putSettings(data.settings)
  }

  /** Changement de PIN : re-chiffre l'intégralité du coffre avec la nouvelle clé. */
  async changePin(newPin: string): Promise<EncryptedRepository> {
    const data = await this.loadAll()
    const salt = randomBytes(16)
    const newKey = await deriveKey(newPin, salt)
    const check = await encryptJson(newKey, 'app-budget-check')
    const next = new EncryptedRepository(this.db, newKey)
    await this.db.meta.bulkPut([
      { key: META_SALT, value: salt },
      { key: META_CHECK, value: check },
    ])
    await next.replaceAll(data)
    return next
  }
}
