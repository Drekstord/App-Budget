import Dexie, { type EntityTable } from 'dexie'

/** Enregistrement chiffré : seuls l'identifiant et la table sont en clair. */
export interface VaultRecord {
  /** `${table}:${entityId}` */
  id: string
  table: string
  iv: Uint8Array
  data: ArrayBuffer
}

export interface MetaRecord {
  key: string
  value: unknown
}

export class BudgetDB extends Dexie {
  vault!: EntityTable<VaultRecord, 'id'>
  meta!: EntityTable<MetaRecord, 'key'>

  constructor() {
    super('app-budget')
    this.version(1).stores({
      vault: 'id, table',
      meta: 'key',
    })
  }
}

let instance: BudgetDB | null = null

export function getDB(): BudgetDB {
  if (!instance) instance = new BudgetDB()
  return instance
}
