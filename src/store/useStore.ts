import { create } from 'zustand'
import { getDB } from '../lib/db.ts'
import {
  clearSession,
  EncryptedRepository,
  isInitialized,
  loadSession,
  saveSession,
  setupVault,
  touchSession,
  unlockVault,
  type EntityTableName,
} from '../lib/repository.ts'
import {
  DEFAULT_SETTINGS,
  nowISO,
  stamp,
  type Account,
  type AppData,
  type BaseEntity,
  type Budget,
  type Category,
  type FundingPlan,
  type Settings,
  type Transaction,
} from '../domain/types.ts'
import { defaultAccount, defaultCategories } from '../domain/defaults.ts'

export type AppPhase = 'loading' | 'setup' | 'locked' | 'unlocked'

type AccountInput = Pick<Account, 'name' | 'type' | 'initialBalance' | 'icon'>
type CategoryInput = Pick<Category, 'name' | 'kind' | 'parentId' | 'icon' | 'colorSlot'>
type TransactionInput = Pick<
  Transaction,
  'type' | 'amount' | 'date' | 'accountId' | 'toAccountId' | 'categoryId' | 'note' | 'payee'
>
type FundingPlanInput = Pick<
  FundingPlan,
  'name' | 'targetAmount' | 'targetLabel' | 'targetDate' | 'accountRules' | 'incomes' | 'expenseEvents'
>

interface AppState {
  phase: AppPhase
  data: AppData | null
  repo: EncryptedRepository | null

  init: () => Promise<void>
  setupPin: (pin: string) => Promise<void>
  unlock: (pin: string) => Promise<boolean>
  lock: () => void
  extendSession: () => Promise<void>
  changePin: (currentPin: string, newPin: string) => Promise<boolean>

  addTransaction: (input: TransactionInput) => Promise<void>
  updateTransaction: (id: string, patch: Partial<TransactionInput>) => Promise<void>
  deleteTransaction: (id: string) => Promise<void>

  addAccount: (input: AccountInput) => Promise<Account>
  updateAccount: (id: string, patch: Partial<AccountInput & { archived: boolean }>) => Promise<void>
  deleteAccount: (id: string) => Promise<boolean>

  addCategory: (input: CategoryInput) => Promise<Category>
  updateCategory: (id: string, patch: Partial<CategoryInput>) => Promise<void>
  deleteCategory: (id: string, reassignToId: string | null) => Promise<void>

  setBudget: (categoryId: string, monthlyAmount: number | null) => Promise<void>

  addFundingPlan: (input: FundingPlanInput) => Promise<FundingPlan>
  updateFundingPlan: (id: string, patch: Partial<FundingPlanInput>) => Promise<void>
  deleteFundingPlan: (id: string) => Promise<void>

  updateSettings: (patch: Partial<Settings>) => Promise<void>
  importData: (data: AppData) => Promise<void>
}

function requireSession(state: AppState): { data: AppData; repo: EncryptedRepository } {
  if (!state.data || !state.repo) throw new Error('Application verrouillée')
  return { data: state.data, repo: state.repo }
}

export const useStore = create<AppState>()((set, get) => {
  /** Applique une mutation sur une liste d'entités puis la persiste. */
  async function persist<T extends BaseEntity>(
    table: EntityTableName,
    entity: T,
    mutate: (list: T[]) => T[],
  ): Promise<void> {
    const { data, repo } = requireSession(get())
    const list = data[table] as unknown as T[]
    set({ data: { ...data, [table]: mutate(list) } })
    await repo.put(table, entity)
  }

  function touch<T extends BaseEntity>(entity: T, patch: Partial<T>): T {
    return { ...entity, ...patch, updatedAt: nowISO() }
  }

  async function softDelete<T extends BaseEntity>(table: EntityTableName, list: T[], id: string) {
    const entity = list.find((e) => e.id === id)
    if (!entity) return
    const dead = touch(entity, { deletedAt: nowISO() } as Partial<T>)
    await persist(table, dead, (l) => l.map((e) => (e.id === id ? dead : e)))
  }

  return {
    phase: 'loading',
    data: null,
    repo: null,

    async init() {
      const db = getDB()
      if (!(await isInitialized(db))) {
        set({ phase: 'setup' })
        return
      }
      // Session de déverrouillage encore valide → pas de PIN au rechargement.
      const sessionKey = await loadSession(db)
      if (sessionKey) {
        try {
          const repo = new EncryptedRepository(db, sessionKey)
          const data = await repo.loadAll()
          set({ phase: 'unlocked', data, repo })
          return
        } catch {
          await clearSession(db)
        }
      }
      set({ phase: 'locked' })
    },

    async setupPin(pin) {
      const db = getDB()
      const key = await setupVault(db, pin)
      const repo = new EncryptedRepository(db, key)
      const account = defaultAccount()
      const categories = defaultCategories()
      const data: AppData = {
        accounts: [account],
        categories,
        transactions: [],
        budgets: [],
        fundingPlans: [],
        settings: { ...DEFAULT_SETTINGS, defaultAccountId: account.id },
      }
      await repo.replaceAll(data)
      await saveSession(db, key, data.settings.lockDelayMinutes)
      set({ phase: 'unlocked', data, repo })
    },

    async unlock(pin) {
      const db = getDB()
      const key = await unlockVault(db, pin)
      if (!key) return false
      const repo = new EncryptedRepository(db, key)
      const data = await repo.loadAll()
      await saveSession(db, key, data.settings.lockDelayMinutes)
      set({ phase: 'unlocked', data, repo })
      return true
    },

    lock() {
      void clearSession(getDB())
      set({ phase: 'locked', data: null, repo: null })
    },

    async extendSession() {
      const delay = get().data?.settings.lockDelayMinutes ?? 5
      await touchSession(getDB(), delay)
    },

    async changePin(currentPin, newPin) {
      const db = getDB()
      const key = await unlockVault(db, currentPin)
      if (!key) return false
      const { repo, data } = requireSession(get())
      const next = await repo.changePin(newPin)
      await saveSession(db, next.key, data.settings.lockDelayMinutes)
      set({ repo: next })
      return true
    },

    async addTransaction(input) {
      const t: Transaction = { ...stamp(), ...input }
      await persist('transactions', t, (list) => [...list, t])
    },

    async updateTransaction(id, patch) {
      const { data } = requireSession(get())
      const existing = data.transactions.find((t) => t.id === id)
      if (!existing) return
      const updated = touch(existing, patch as Partial<Transaction>)
      await persist('transactions', updated, (list) =>
        list.map((t) => (t.id === id ? updated : t)),
      )
    },

    async deleteTransaction(id) {
      const { data } = requireSession(get())
      await softDelete('transactions', data.transactions, id)
    },

    async addAccount(input) {
      const account: Account = { ...stamp(), ...input, archived: false }
      await persist('accounts', account, (list) => [...list, account])
      return account
    },

    async updateAccount(id, patch) {
      const { data } = requireSession(get())
      const existing = data.accounts.find((a) => a.id === id)
      if (!existing) return
      const updated = touch(existing, patch as Partial<Account>)
      await persist('accounts', updated, (list) => list.map((a) => (a.id === id ? updated : a)))
    },

    /** Refuse la suppression si des transactions vivantes utilisent le compte. */
    async deleteAccount(id) {
      const { data } = requireSession(get())
      const used = data.transactions.some(
        (t) => !t.deletedAt && (t.accountId === id || t.toAccountId === id),
      )
      if (used) return false
      await softDelete('accounts', data.accounts, id)
      if (get().data?.settings.defaultAccountId === id) {
        await get().updateSettings({ defaultAccountId: null })
      }
      return true
    },

    async addCategory(input) {
      const category: Category = { ...stamp(), ...input }
      await persist('categories', category, (list) => [...list, category])
      return category
    },

    async updateCategory(id, patch) {
      const { data } = requireSession(get())
      const existing = data.categories.find((c) => c.id === id)
      if (!existing) return
      const updated = touch(existing, patch as Partial<Category>)
      await persist('categories', updated, (list) => list.map((c) => (c.id === id ? updated : c)))
    },

    /** Supprime une catégorie en réaffectant ses transactions et sous-catégories. */
    async deleteCategory(id, reassignToId) {
      const { data, repo } = requireSession(get())
      const transactions = data.transactions.map((t) =>
        !t.deletedAt && t.categoryId === id
          ? { ...t, categoryId: reassignToId, updatedAt: nowISO() }
          : t,
      )
      const categories = data.categories.map((c) => {
        if (c.id === id && !c.deletedAt) return { ...c, deletedAt: nowISO(), updatedAt: nowISO() }
        if (c.parentId === id && !c.deletedAt) return { ...c, parentId: null, updatedAt: nowISO() }
        return c
      })
      const budgets = data.budgets.map((b) =>
        !b.deletedAt && b.categoryId === id ? { ...b, deletedAt: nowISO(), updatedAt: nowISO() } : b,
      )
      set({ data: { ...data, transactions, categories, budgets } })
      for (const t of transactions.filter((t, i) => t !== data.transactions[i])) {
        await repo.put('transactions', t)
      }
      for (const c of categories.filter((c, i) => c !== data.categories[i])) {
        await repo.put('categories', c)
      }
      for (const b of budgets.filter((b, i) => b !== data.budgets[i])) {
        await repo.put('budgets', b)
      }
    },

    /** Définit, met à jour ou supprime (montant null) le budget d'une catégorie. */
    async setBudget(categoryId, monthlyAmount) {
      const { data } = requireSession(get())
      const existing = data.budgets.find((b) => !b.deletedAt && b.categoryId === categoryId)
      if (monthlyAmount === null) {
        if (existing) await softDelete('budgets', data.budgets, existing.id)
        return
      }
      if (existing) {
        const updated = touch(existing, { monthlyAmount })
        await persist('budgets', updated, (list) =>
          list.map((b) => (b.id === existing.id ? updated : b)),
        )
      } else {
        const budget: Budget = { ...stamp(), categoryId, monthlyAmount }
        await persist('budgets', budget, (list) => [...list, budget])
      }
    },

    async addFundingPlan(input) {
      const plan: FundingPlan = { ...stamp(), ...input }
      await persist('fundingPlans', plan, (list) => [...list, plan])
      return plan
    },

    async updateFundingPlan(id, patch) {
      const { data } = requireSession(get())
      const existing = data.fundingPlans.find((p) => p.id === id)
      if (!existing) return
      const updated = touch(existing, patch as Partial<FundingPlan>)
      await persist('fundingPlans', updated, (list) =>
        list.map((p) => (p.id === id ? updated : p)),
      )
    },

    async deleteFundingPlan(id) {
      const { data } = requireSession(get())
      await softDelete('fundingPlans', data.fundingPlans, id)
    },

    async updateSettings(patch) {
      const { data, repo } = requireSession(get())
      const settings = { ...data.settings, ...patch }
      set({ data: { ...data, settings } })
      await repo.putSettings(settings)
      if (patch.lockDelayMinutes !== undefined) {
        await saveSession(getDB(), repo.key, settings.lockDelayMinutes)
      }
    },

    async importData(imported) {
      const { repo } = requireSession(get())
      await repo.replaceAll(imported)
      set({ data: imported })
    },
  }
})
