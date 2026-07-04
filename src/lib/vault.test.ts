// Tests du coffre chiffré (crypto + repository) sur IndexedDB simulé.
import 'fake-indexeddb/auto'
import { describe, expect, it } from 'vitest'
import { decryptJson, deriveKey, encryptJson, randomBytes } from './crypto.ts'
import { BudgetDB } from './db.ts'
import { EncryptedRepository, isInitialized, setupVault, unlockVault } from './repository.ts'
import { stamp, type Transaction } from '../domain/types.ts'

function tx(): Transaction {
  return {
    ...stamp(),
    type: 'expense',
    amount: 1250,
    date: '2026-07-03',
    accountId: 'a1',
    toAccountId: null,
    categoryId: 'c1',
    note: 'café',
    payee: '',
  }
}

describe('crypto', () => {
  it('chiffre et déchiffre un objet', async () => {
    const key = await deriveKey('1234', randomBytes(16))
    const blob = await encryptJson(key, { hello: 'monde' })
    expect(await decryptJson(key, blob)).toEqual({ hello: 'monde' })
  })

  it('échoue avec une mauvaise clé', async () => {
    const salt = randomBytes(16)
    const good = await deriveKey('1234', salt)
    const bad = await deriveKey('9999', salt)
    const blob = await encryptJson(good, 'secret')
    await expect(decryptJson(bad, blob)).rejects.toThrow()
  })
})

describe('vault', () => {
  it('initialise, verrouille et déverrouille avec le bon PIN', async () => {
    const db = new BudgetDB()
    expect(await isInitialized(db)).toBe(false)
    await setupVault(db, '1234')
    expect(await isInitialized(db)).toBe(true)
    expect(await unlockVault(db, '0000')).toBeNull()
    expect(await unlockVault(db, '1234')).not.toBeNull()
    await db.delete()
  })

  it('persiste et relit des entités chiffrées', async () => {
    const db = new BudgetDB()
    const key = await setupVault(db, '1234')
    const repo = new EncryptedRepository(db, key)
    const t = tx()
    await repo.put('transactions', t)
    const data = await repo.loadAll()
    expect(data.transactions).toEqual([t])
    // Le contenu stocké est bien chiffré : la note n'apparaît pas en clair.
    const raw = await db.vault.toArray()
    const stored = new TextDecoder().decode(raw[0].data)
    expect(stored).not.toContain('café')
    await db.delete()
  })

  it('re-chiffre tout lors d’un changement de PIN', async () => {
    const db = new BudgetDB()
    const key = await setupVault(db, '1234')
    const repo = new EncryptedRepository(db, key)
    const t = tx()
    await repo.put('transactions', t)
    await repo.changePin('567890')
    expect(await unlockVault(db, '1234')).toBeNull()
    const newKey = await unlockVault(db, '567890')
    expect(newKey).not.toBeNull()
    const data = await new EncryptedRepository(db, newKey!).loadAll()
    expect(data.transactions).toEqual([t])
    await db.delete()
  })
})
