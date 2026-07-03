import { useEffect, useMemo, useRef, useState } from 'react'
import { useStore } from '../store/useStore.ts'
import { alive, type Transaction, type TransactionType } from '../domain/types.ts'
import { centsToInput, parseAmountToCents } from '../domain/money.ts'
import { todayISO } from '../domain/periods.ts'
import { Modal } from './Modal.tsx'

interface TransactionFormProps {
  open: boolean
  onClose: () => void
  /** Transaction à modifier ; absente = saisie rapide d'une nouvelle opération. */
  transaction?: Transaction
}

const TYPE_LABELS: Record<TransactionType, string> = {
  expense: 'Dépense',
  income: 'Revenu',
  transfer: 'Virement',
}

export function TransactionForm({ open, onClose, transaction }: TransactionFormProps) {
  const data = useStore((s) => s.data)
  const addTransaction = useStore((s) => s.addTransaction)
  const updateTransaction = useStore((s) => s.updateTransaction)
  const deleteTransaction = useStore((s) => s.deleteTransaction)

  const accounts = useMemo(
    () => (data ? alive(data.accounts).filter((a) => !a.archived) : []),
    [data],
  )
  const defaultAccountId =
    data?.settings.defaultAccountId && accounts.some((a) => a.id === data.settings.defaultAccountId)
      ? data.settings.defaultAccountId
      : (accounts[0]?.id ?? '')

  const [type, setType] = useState<TransactionType>('expense')
  const [amount, setAmount] = useState('')
  const [categoryId, setCategoryId] = useState<string | null>(null)
  const [accountId, setAccountId] = useState(defaultAccountId)
  const [toAccountId, setToAccountId] = useState('')
  const [date, setDate] = useState(todayISO())
  const [payee, setPayee] = useState('')
  const [note, setNote] = useState('')
  const [showDetails, setShowDetails] = useState(false)
  const [error, setError] = useState('')
  const amountRef = useRef<HTMLInputElement>(null)

  // (Ré)initialise le formulaire à chaque ouverture.
  useEffect(() => {
    if (!open) return
    if (transaction) {
      setType(transaction.type)
      setAmount(centsToInput(transaction.amount))
      setCategoryId(transaction.categoryId)
      setAccountId(transaction.accountId)
      setToAccountId(transaction.toAccountId ?? '')
      setDate(transaction.date)
      setPayee(transaction.payee)
      setNote(transaction.note)
      setShowDetails(Boolean(transaction.payee || transaction.note))
    } else {
      setType('expense')
      setAmount('')
      setCategoryId(null)
      setAccountId(defaultAccountId)
      setToAccountId('')
      setDate(todayISO())
      setPayee('')
      setNote('')
      setShowDetails(false)
    }
    setError('')
    setTimeout(() => amountRef.current?.focus(), 50)
  }, [open, transaction, defaultAccountId])

  if (!data) return null
  const categories = alive(data.categories).filter(
    (c) => c.kind === (type === 'income' ? 'income' : 'expense'),
  )

  const submit = async () => {
    const cents = parseAmountToCents(amount)
    if (cents === null || cents <= 0) {
      setError('Saisis un montant valide, par exemple 12,50.')
      return
    }
    if (!accountId) {
      setError('Choisis un compte.')
      return
    }
    if (type === 'transfer' && (!toAccountId || toAccountId === accountId)) {
      setError('Choisis deux comptes différents pour le virement.')
      return
    }
    if (type !== 'transfer' && !categoryId) {
      setError('Choisis une catégorie.')
      return
    }
    const payload = {
      type,
      amount: cents,
      date,
      accountId,
      toAccountId: type === 'transfer' ? toAccountId : null,
      categoryId: type === 'transfer' ? null : categoryId,
      payee: payee.trim(),
      note: note.trim(),
    }
    if (transaction) await updateTransaction(transaction.id, payload)
    else await addTransaction(payload)
    onClose()
  }

  const remove = async () => {
    if (!transaction) return
    if (!window.confirm('Supprimer cette opération ?')) return
    await deleteTransaction(transaction.id)
    onClose()
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={transaction ? 'Modifier l’opération' : 'Nouvelle opération'}
    >
      <form
        onSubmit={(e) => {
          e.preventDefault()
          void submit()
        }}
      >
        <div className="field">
          <span className="field-label" id="tx-type-label">
            Type
          </span>
          <div className="chip-row" role="group" aria-labelledby="tx-type-label">
            {(Object.keys(TYPE_LABELS) as TransactionType[]).map((t) => (
              <button
                key={t}
                type="button"
                className="chip"
                aria-pressed={type === t}
                onClick={() => {
                  setType(t)
                  setCategoryId(null)
                }}
              >
                {TYPE_LABELS[t]}
              </button>
            ))}
          </div>
        </div>

        <div className="field">
          <label htmlFor="tx-amount">Montant (€)</label>
          <input
            id="tx-amount"
            ref={amountRef}
            inputMode="decimal"
            autoComplete="off"
            placeholder="0,00"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            style={{ fontSize: '1.4rem', fontWeight: 700 }}
          />
        </div>

        {type !== 'transfer' && (
          <div className="field">
            <span className="field-label" id="tx-cat-label">
              Catégorie
            </span>
            <div className="chip-row" role="group" aria-labelledby="tx-cat-label">
              {categories.map((c) => (
                <button
                  key={c.id}
                  type="button"
                  className="chip"
                  aria-pressed={categoryId === c.id}
                  onClick={() => setCategoryId(c.id)}
                >
                  <span aria-hidden="true">{c.icon} </span>
                  {c.name}
                </button>
              ))}
            </div>
          </div>
        )}

        <div className="field">
          <label htmlFor="tx-account">{type === 'transfer' ? 'Depuis le compte' : 'Compte'}</label>
          <select id="tx-account" value={accountId} onChange={(e) => setAccountId(e.target.value)}>
            {accounts.map((a) => (
              <option key={a.id} value={a.id}>
                {a.name}
              </option>
            ))}
          </select>
        </div>

        {type === 'transfer' && (
          <div className="field">
            <label htmlFor="tx-to-account">Vers le compte</label>
            <select
              id="tx-to-account"
              value={toAccountId}
              onChange={(e) => setToAccountId(e.target.value)}
            >
              <option value="">— Choisir —</option>
              {accounts
                .filter((a) => a.id !== accountId)
                .map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.name}
                  </option>
                ))}
            </select>
          </div>
        )}

        <div className="field">
          <label htmlFor="tx-date">Date</label>
          <input id="tx-date" type="date" value={date} onChange={(e) => setDate(e.target.value)} />
        </div>

        {showDetails ? (
          <>
            <div className="field">
              <label htmlFor="tx-payee">Bénéficiaire / marchand</label>
              <input
                id="tx-payee"
                value={payee}
                onChange={(e) => setPayee(e.target.value)}
                autoComplete="off"
              />
            </div>
            <div className="field">
              <label htmlFor="tx-note">Note</label>
              <input id="tx-note" value={note} onChange={(e) => setNote(e.target.value)} />
            </div>
          </>
        ) : (
          <button type="button" className="btn btn-ghost" onClick={() => setShowDetails(true)}>
            + Ajouter une note ou un marchand
          </button>
        )}

        {error && (
          <p className="pin-error" role="alert">
            {error}
          </p>
        )}

        <div style={{ display: 'flex', gap: '0.5rem', marginTop: '1rem' }}>
          {transaction && (
            <button type="button" className="btn btn-danger" onClick={() => void remove()}>
              Supprimer
            </button>
          )}
          <button type="submit" className="btn btn-primary" style={{ flex: 1 }}>
            {transaction ? 'Enregistrer' : 'Ajouter'}
          </button>
        </div>
      </form>
    </Modal>
  )
}
