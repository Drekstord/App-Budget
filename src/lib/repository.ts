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
const SETTINGS_ID = 'settings:singleton'

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
    private key: CryptoKey,
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
