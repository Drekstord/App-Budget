import { useState } from 'react'
import { useStore } from '../store/useStore.ts'
import { alive, type Account, type AccountType } from '../domain/types.ts'
import { centsToInput, formatEUR, parseAmountToCents } from '../domain/money.ts'
import { accountBalance, totalBalance } from '../domain/stats.ts'
import { Modal } from '../components/Modal.tsx'

const TYPE_LABELS: Record<AccountType, string> = {
  checking: 'Compte courant',
  savings: 'Épargne',
  cash: 'Espèces',
  card: 'Carte',
  other: 'Autre',
}

const TYPE_ICONS: Record<AccountType, string> = {
  checking: '🏦',
  savings: '🐷',
  cash: '💵',
  card: '💳',
  other: '📁',
}

export function AccountsPage() {
  const data = useStore((s) => s.data)
  const addAccount = useStore((s) => s.addAccount)
  const updateAccount = useStore((s) => s.updateAccount)
  const deleteAccount = useStore((s) => s.deleteAccount)
  const updateSettings = useStore((s) => s.updateSettings)

  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<Account | null>(null)
  const [name, setName] = useState('')
  const [type, setType] = useState<AccountType>('checking')
  const [initialBalance, setInitialBalance] = useState('0')
  const [error, setError] = useState('')

  if (!data) return null
  const accounts = alive(data.accounts).filter((a) => !a.archived)

  const openForm = (account: Account | null) => {
    setEditing(account)
    setName(account?.name ?? '')
    setType(account?.type ?? 'checking')
    setInitialBalance(account ? centsToInput(account.initialBalance) : '0')
    setError('')
    setOpen(true)
  }

  const save = async () => {
    const cents = parseAmountToCents(initialBalance)
    if (!name.trim()) {
      setError('Donne un nom au compte.')
      return
    }
    if (cents === null) {
      setError('Solde initial invalide.')
      return
    }
    if (editing) {
      await updateAccount(editing.id, { name: name.trim(), type, initialBalance: cents, icon: TYPE_ICONS[type] })
    } else {
      const account = await addAccount({
        name: name.trim(),
        type,
        initialBalance: cents,
        icon: TYPE_ICONS[type],
      })
      if (!data.settings.defaultAccountId) {
        await updateSettings({ defaultAccountId: account.id })
      }
    }
    setOpen(false)
  }

  const remove = async () => {
    if (!editing) return
    if (!window.confirm(`Supprimer le compte « ${editing.name} » ?`)) return
    const ok = await deleteAccount(editing.id)
    if (!ok) {
      setError('Ce compte a des opérations : supprime-les ou déplace-les d’abord.')
      return
    }
    setOpen(false)
  }

  return (
    <div className="stack">
      <div className="kpi">
        <div className="kpi-label">Solde total</div>
        <div className="kpi-value">{formatEUR(totalBalance(data.accounts, data.transactions))}</div>
      </div>

      <ul className="list card" style={{ padding: '0 0.75rem' }}>
        {accounts.map((a) => (
          <li key={a.id} className="list-item">
            <span className="item-icon" aria-hidden="true">
              {a.icon}
            </span>
            <span className="item-body">
              <span className="item-title">
                {a.name}
                {data.settings.defaultAccountId === a.id && (
                  <span className="item-sub"> · par défaut</span>
                )}
              </span>
              <br />
              <span className="item-sub">{TYPE_LABELS[a.type]}</span>
            </span>
            <span className="amount">{formatEUR(accountBalance(a, data.transactions))}</span>
            <button
              type="button"
              className="icon-btn"
              aria-label={`Modifier le compte ${a.name}`}
              onClick={() => openForm(a)}
            >
              ✏️
            </button>
          </li>
        ))}
      </ul>

      <button type="button" className="btn btn-primary" onClick={() => openForm(null)}>
        + Ajouter un compte
      </button>

      <p className="chart-note">
        Astuce : pour déplacer de l’argent entre deux comptes, ajoute une opération de type
        « Virement » depuis l’écran Opérations.
      </p>

      <Modal
        open={open}
        onClose={() => setOpen(false)}
        title={editing ? 'Modifier le compte' : 'Nouveau compte'}
      >
        <form
          onSubmit={(e) => {
            e.preventDefault()
            void save()
          }}
        >
          <div className="field">
            <label htmlFor="acc-name">Nom</label>
            <input id="acc-name" value={name} onChange={(e) => setName(e.target.value)} autoFocus />
          </div>
          <div className="field">
            <label htmlFor="acc-type">Type</label>
            <select id="acc-type" value={type} onChange={(e) => setType(e.target.value as AccountType)}>
              {(Object.keys(TYPE_LABELS) as AccountType[]).map((t) => (
                <option key={t} value={t}>
                  {TYPE_LABELS[t]}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor="acc-balance">Solde initial (€)</label>
            <input
              id="acc-balance"
              inputMode="decimal"
              value={initialBalance}
              onChange={(e) => setInitialBalance(e.target.value)}
            />
          </div>
          {editing && (
            <div className="field">
              <label>
                <input
                  type="checkbox"
                  style={{ width: 'auto', minHeight: 'auto', marginRight: '0.5rem' }}
                  checked={data.settings.defaultAccountId === editing.id}
                  onChange={(e) =>
                    void updateSettings({ defaultAccountId: e.target.checked ? editing.id : null })
                  }
                />
                Compte par défaut pour la saisie rapide
              </label>
            </div>
          )}
          {error && (
            <p className="pin-error" role="alert">
              {error}
            </p>
          )}
          <div style={{ display: 'flex', gap: '0.5rem' }}>
            {editing && (
              <button type="button" className="btn btn-danger" onClick={() => void remove()}>
                Supprimer
              </button>
            )}
            <button type="submit" className="btn btn-primary" style={{ flex: 1 }}>
              Enregistrer
            </button>
          </div>
        </form>
      </Modal>
    </div>
  )
}
