// Sauvegarde / restauration : JSON complet, tombstones inclus, versionné
// pour rester lisible par les versions futures.

import { DEFAULT_SETTINGS, type AppData } from './types.ts'

export const BACKUP_FORMAT = 'app-budget-backup'
export const BACKUP_VERSION = 1

export interface BackupFile {
  format: typeof BACKUP_FORMAT
  version: number
  exportedAt: string
  data: AppData
}

export function createBackup(data: AppData): BackupFile {
  return {
    format: BACKUP_FORMAT,
    version: BACKUP_VERSION,
    exportedAt: new Date().toISOString(),
    data,
  }
}

export function backupFileName(date = new Date()): string {
  return `app-budget-sauvegarde-${date.toISOString().slice(0, 10)}.json`
}

export class BackupError extends Error {}

export function parseBackup(json: string): AppData {
  let parsed: unknown
  try {
    parsed = JSON.parse(json)
  } catch {
    throw new BackupError('Le fichier n’est pas un JSON valide.')
  }
  const file = parsed as Partial<BackupFile>
  if (file.format !== BACKUP_FORMAT) {
    throw new BackupError('Ce fichier n’est pas une sauvegarde App Budget.')
  }
  if (typeof file.version !== 'number' || file.version > BACKUP_VERSION) {
    throw new BackupError(
      'Cette sauvegarde vient d’une version plus récente de l’application.',
    )
  }
  const data = file.data
  if (
    !data ||
    !Array.isArray(data.accounts) ||
    !Array.isArray(data.categories) ||
    !Array.isArray(data.transactions) ||
    !Array.isArray(data.budgets) ||
    typeof data.settings !== 'object'
  ) {
    throw new BackupError('La sauvegarde est incomplète ou corrompue.')
  }
  return {
    accounts: data.accounts,
    categories: data.categories,
    transactions: data.transactions,
    budgets: data.budgets,
    // Champs ajoutés après la V1 : absents des sauvegardes anciennes.
    fundingPlans: Array.isArray(data.fundingPlans) ? data.fundingPlans : [],
    subscriptions: Array.isArray(data.subscriptions) ? data.subscriptions : [],
    settings: { ...DEFAULT_SETTINGS, ...data.settings },
  }
}
