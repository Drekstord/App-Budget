import { useState } from 'react'
import { useStore } from '../store/useStore.ts'
import { alive, type Account, type AccountType } from '../domain/types.ts'
import { centsToInput, formatEUR, parseAmountToCents } from '../domain/money.ts'
import { accountBalance, initialBalanceForTarget, totalBalance } from '../domain/stats.ts'
import { Modal } from '../components/Modal.tsx'
import { IconEdit, IconPlus } from '../components/icons.tsx'

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
  // On saisit le solde *actuel* (celui affiché par la banque) : le solde de
  // départ est recalculé pour retomber dessus, sans toucher aux opérations.
  const [balance, setBalance] = useState('0')
  const [overdraft, setOverdraft] = useState('0')
  const [error, setError] = useState('')

  if (!data) return null
  const accounts = alive(data.accounts).filter((a) => !a.archived)
  const movementCount = editing
    ? alive(data.transactions).filter(
        (t) => t.accountId === editing.id || t.toAccountId === editing.id,
      ).length
    : 0

  const openForm = (account: Account | null) => {
    setEditing(account)
    setName(account?.name ?? '')
    setType(account?.type ?? 'checking')
    setBalance(account ? centsToInput(accountBalance(account, data.transactions)) : '0')
    setOverdraft(account?.overdraft ? centsToInput(account.overdraft) : '0')
    setError('')
    setOpen(true)
  }

  /** Inverse le signe : le pavé décimal mobile n'offre pas toujours le « − ». */
  const toggleSign = () => {
    setBalance((v) => {
      const t = v.trim()
      if (t === '' || t === '0' || t === '0,00') return t
      return t.startsWith('-') ? t.slice(1) : `-${t}`
    })
  }

  const save = async () => {
    const cents = parseAmountToCents(balance)
    const overdraftCents = parseAmountToCents(overdraft)
    if (!name.trim()) {
      setError('Donne un nom au compte.')
      return
    }
    if (cents === null) {
      setError('Solde invalide. Exemples : 1250,40 ou -310,50.')
      return
    }
    if (overdraftCents === null || overdraftCents < 0) {
      setError('Découvert autorisé invalide (0 si aucun).')
      return
    }
    if (editing) {
      await updateAccount(editing.id, {
        name: name.trim(),
        type,
        initialBalance: initialBalanceForTarget(editing, data.transactions, cents),
        overdraft: overdraftCents,
        icon: TYPE_ICONS[type],
      })
    } else {
      const account = await addAccount({
        name: name.trim(),
        type,
        initialBalance: cents,
        overdraft: overdraftCents,
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
    <>
      <section className="card" aria-label="Solde total">
        <span className="label">Solde total</span>
        <p className="hero hero-sm">{formatEUR(totalBalance(data.accounts, data.transactions))}</p>
        <p className="hint">
          {accounts.length} compte{accounts.length > 1 ? 's' : ''} actif
          {accounts.length > 1 ? 's' : ''}
        </p>
      </section>

      <section className="card">
        <ul className="rows">
          {accounts.map((a) => (
            <li key={a.id} className="row" style={{ padding: 0 }}>
              <button
                type="button"
                className="row-btn"
                aria-label={`Modifier le compte ${a.name}`}
                onClick={() => openForm(a)}
              >
                <span className="glyph" aria-hidden="true">
                  {a.icon}
                </span>
                <span className="row-main">
                  {/* Le nom garde toute la largeur : « par défaut » et le
                      découvert vivent sur la ligne secondaire. */}
                  <span className="row-title">{a.name}</span>
                  <span className="row-meta">
                    {TYPE_LABELS[a.type]}
                    {a.overdraft > 0 && ` · découvert ${formatEUR(a.overdraft)}`}
                    {data.settings.defaultAccountId === a.id && ' · par défaut'}
                  </span>
                </span>
                <span className="amount">{formatEUR(accountBalance(a, data.transactions))}</span>
                <IconEdit className="chev" size={16} />
              </button>
            </li>
          ))}
        </ul>
      </section>

      <button type="button" className="btn btn-primary" onClick={() => openForm(null)}>
        <IconPlus size={18} /> Ajouter un compte
      </button>

      <p className="hint">
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
            <label htmlFor="acc-balance">
              {editing ? 'Solde actuel (€)' : 'Solde de départ (€)'}
            </label>
            <div className="signed-field">
              <input
                id="acc-balance"
                inputMode="decimal"
                value={balance}
                onChange={(e) => setBalance(e.target.value)}
                placeholder="0,00"
              />
              <button
                type="button"
                className="btn btn-sm"
                onClick={toggleSign}
                aria-label={
                  balance.trim().startsWith('-')
                    ? 'Rendre le solde positif'
                    : 'Rendre le solde négatif'
                }
                title="Inverser le signe"
              >
                ±
              </button>
            </div>
            <p className="hint">
              {editing
                ? movementCount > 0
                  ? `Ce que ta banque affiche aujourd’hui. Un montant négatif est accepté (ex. -310,50). Tes ${movementCount} opération${movementCount > 1 ? 's' : ''} sur ce compte sont conservées : c’est le solde de départ qui est recalculé.`
                  : 'Ce que ta banque affiche aujourd’hui. Un montant négatif est accepté (ex. -310,50).'
                : 'Le solde du compte au moment où tu l’ajoutes. Un montant négatif est accepté (ex. -310,50).'}
            </p>
          </div>
          <div className="field">
            <label htmlFor="acc-overdraft">Découvert autorisé (€)</label>
            <input
              id="acc-overdraft"
              inputMode="decimal"
              value={overdraft}
              onChange={(e) => setOverdraft(e.target.value)}
              placeholder="0"
            />
            <p className="hint">
              Montant jusqu’auquel ce compte peut passer en négatif sans frais. Utilisé par les
              plans de financement comme trésorerie mobilisable. 0 si aucun.
            </p>
          </div>
          {editing && (
            <div className="field">
              <label className="field-inline">
                <input
                  type="checkbox"
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
          <div className="sheet-actions">
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
    </>
  )
}
