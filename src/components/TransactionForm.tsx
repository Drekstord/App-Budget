import { useEffect, useMemo, useRef, useState } from 'react'
import { useStore } from '../store/useStore.ts'
import { alive, type Category, type Transaction, type TransactionType } from '../domain/types.ts'
import { centsToInput, formatEUR, parseAmountToCents } from '../domain/money.ts'
import { todayISO } from '../domain/periods.ts'
import { parseReceipt, type ParsedReceipt } from '../domain/receipt.ts'
import { computeTransactionAlerts } from '../domain/alerts.ts'
import { notifySystem, useToasts } from '../store/toasts.ts'
import { Modal } from './Modal.tsx'
import { IconBackspace, IconCamera } from './icons.tsx'

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

type ScanState = 'idle' | 'processing' | 'done' | 'error'

/** Touches du pavé : chiffres, séparateur décimal, effacement. */
const KEYS = ['1', '2', '3', '4', '5', '6', '7', '8', '9', ',', '0', 'del'] as const

export function TransactionForm({ open, onClose, transaction }: TransactionFormProps) {
  const data = useStore((s) => s.data)
  const addTransaction = useStore((s) => s.addTransaction)
  const updateTransaction = useStore((s) => s.updateTransaction)
  const deleteTransaction = useStore((s) => s.deleteTransaction)
  const pushToasts = useToasts((s) => s.push)

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
  const scanInputRef = useRef<HTMLInputElement>(null)
  const [scanState, setScanState] = useState<ScanState>('idle')
  const [scanProgress, setScanProgress] = useState(0)
  const [scanSummary, setScanSummary] = useState('')

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
    setScanState('idle')
    setScanProgress(0)
    setScanSummary('')
  }, [open, transaction, defaultAccountId])

  if (!data) return null
  const categories = alive(data.categories).filter(
    (c) => c.kind === (type === 'income' ? 'income' : 'expense'),
  )

  /** Frappe du pavé : on n'accepte qu'un séparateur et deux décimales. */
  const press = (key: string) => {
    setError('')
    setAmount((cur) => {
      if (key === 'del') return cur.slice(0, -1)
      if (key === ',') return cur.includes(',') ? cur : (cur === '' ? '0,' : cur + ',')
      const [, dec] = cur.split(',')
      if (dec !== undefined && dec.length >= 2) return cur
      if (cur === '0') return key
      return cur + key
    })
  }

  /** Catégorie suggérée : d'abord l'historique du même marchand, sinon l'indice du ticket. */
  const suggestCategory = (parsed: ParsedReceipt): Category | null => {
    const expenseCategories = alive(data.categories).filter((c) => c.kind === 'expense')
    if (parsed.merchant) {
      const needle = parsed.merchant.toLowerCase()
      const past = alive(data.transactions)
        .filter((t) => t.categoryId && t.payee.toLowerCase() === needle)
        .sort((a, b) => b.date.localeCompare(a.date))[0]
      if (past) {
        const cat = expenseCategories.find((c) => c.id === past.categoryId)
        if (cat) return cat
      }
    }
    if (parsed.categoryHint) {
      const hint = parsed.categoryHint.toLowerCase()
      const cat = expenseCategories.find((c) => c.name.toLowerCase() === hint)
      if (cat) return cat
    }
    return null
  }

  const onScanFile = async (file: File) => {
    setScanState('processing')
    setScanProgress(0)
    setScanSummary('')
    setError('')
    try {
      const { recognizeReceipt, OcrError } = await import('../lib/ocr.ts')
      const text = await recognizeReceipt(file, setScanProgress).catch((e) => {
        if (e instanceof OcrError) {
          const messages: Record<string, string> = {
            engine:
              'Le moteur de lecture n’a pas pu se charger. Une connexion est nécessaire au tout premier scan : connecte-toi puis réessaie.',
            timeout:
              'La lecture a été trop longue et a été interrompue. Réessaie avec une photo plus nette, cadrée sur le ticket.',
            recognition: 'La lecture a échoué. Réessaie avec une photo nette et bien éclairée.',
          }
          throw new Error(messages[e.message] ?? messages.recognition)
        }
        throw e
      })
      const parsed = parseReceipt(text)
      if (!parsed.amountCents && !parsed.merchant && !parsed.date) {
        setScanState('error')
        setScanSummary(
          'Impossible de lire ce ticket. Réessaie avec une photo nette, bien éclairée, prise du dessus.',
        )
        return
      }
      setType('expense')
      if (parsed.amountCents) setAmount(centsToInput(parsed.amountCents))
      if (parsed.date) setDate(parsed.date)
      if (parsed.merchant) {
        setPayee(parsed.merchant)
        setShowDetails(true)
      }
      const suggested = suggestCategory(parsed)
      if (suggested) setCategoryId(suggested.id)
      const detected = [
        parsed.amountCents ? formatEUR(parsed.amountCents) : 'montant non lu',
        parsed.merchant ?? 'lieu non lu',
        parsed.date
          ? new Date(parsed.date + 'T00:00:00').toLocaleDateString('fr-FR')
          : 'date non lue',
        suggested ? `catégorie proposée : ${suggested.name}` : null,
      ].filter(Boolean)
      setScanSummary(`Détecté : ${detected.join(' · ')}.`)
      setScanState('done')
    } catch (e) {
      setScanState('error')
      setScanSummary(
        e instanceof Error && e.message
          ? e.message
          : 'Le scan a échoué. Vérifie ta connexion (nécessaire au premier scan) et réessaie.',
      )
    }
  }

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
    if (transaction) {
      await updateTransaction(transaction.id, payload)
    } else {
      // Alertes calculées sur l'état AVANT ajout pour détecter les franchissements.
      const alerts = computeTransactionAlerts(data, payload)
      await addTransaction(payload)
      if (alerts.length > 0) {
        pushToasts(alerts)
        void notifySystem(alerts, data.settings.systemNotifications)
      }
    }
    onClose()
  }

  const remove = async () => {
    if (!transaction) return
    if (!window.confirm('Supprimer cette opération ?')) return
    await deleteTransaction(transaction.id)
    onClose()
  }

  const scanButton = !transaction && (
    <button
      type="button"
      className="icon-btn"
      disabled={scanState === 'processing'}
      onClick={() => scanInputRef.current?.click()}
      aria-label="Scanner un ticket de caisse"
      title="Scanner un ticket de caisse"
    >
      <IconCamera />
    </button>
  )

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={transaction ? 'Modifier l’opération' : 'Nouvelle opération'}
      action={scanButton || undefined}
    >
      <form
        onSubmit={(e) => {
          e.preventDefault()
          void submit()
        }}
      >
        {!transaction && (
          <>
            <input
              ref={scanInputRef}
              type="file"
              accept="image/*"
              capture="environment"
              className="visually-hidden"
              aria-label="Photo du ticket de caisse"
              onChange={(e) => {
                const file = e.target.files?.[0]
                if (file) void onScanFile(file)
                e.target.value = ''
              }}
            />
            {scanState === 'processing' && (
              <div
                className="scan-progress"
                role="progressbar"
                aria-valuenow={Math.round(scanProgress * 100)}
                aria-valuemin={0}
                aria-valuemax={100}
                aria-label="Progression de la lecture du ticket"
              >
                <span style={{ width: `${Math.round(scanProgress * 100)}%` }} />
              </div>
            )}
            {scanSummary && (
              <p className="scan-summary" role="status">
                {scanSummary}
                {scanState === 'done' && (
                  <>
                    <br />
                    <strong>Vérifie les champs ci-dessous, puis confirme avec « Ajouter ».</strong>
                  </>
                )}
              </p>
            )}
          </>
        )}

        {/* Type d'opération : trois onglets segmentés, sans libellé superflu. */}
        <div className="segmented" role="group" aria-label="Type d’opération">
          {(Object.keys(TYPE_LABELS) as TransactionType[]).map((t) => (
            <button
              key={t}
              type="button"
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

        {/* Le montant est le cœur de la saisie : champ réel, affiché en grand. */}
        <div className="amount-field">
          <label htmlFor="tx-amount" className="visually-hidden">
            Montant en euros
          </label>
          <input
            id="tx-amount"
            ref={amountRef}
            inputMode="decimal"
            autoComplete="off"
            placeholder="0,00"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            className="amount-input"
          />
          <span className="amount-unit" aria-hidden="true">
            €
          </span>
        </div>

        <div className="numpad" aria-label="Pavé numérique">
          {KEYS.map((k) => (
            <button
              key={k}
              type="button"
              onClick={() => press(k)}
              aria-label={k === 'del' ? 'Effacer le dernier chiffre' : k === ',' ? 'Virgule' : k}
            >
              {k === 'del' ? <IconBackspace /> : k}
            </button>
          ))}
        </div>

        {type !== 'transfer' && (
          <div className="field" style={{ marginTop: '0.85rem' }}>
            <span className="field-label" id="tx-cat-label">
              Catégorie
            </span>
            <div className="chip-row chip-scroll" role="group" aria-labelledby="tx-cat-label">
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

        <div className={type === 'transfer' ? 'field' : 'grid-2'}>
          <div className="field">
            <label htmlFor="tx-account">
              {type === 'transfer' ? 'Depuis le compte' : 'Compte'}
            </label>
            <select
              id="tx-account"
              value={accountId}
              onChange={(e) => setAccountId(e.target.value)}
            >
              {accounts.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.name}
                </option>
              ))}
            </select>
          </div>
          {type !== 'transfer' && (
            <div className="field">
              <label htmlFor="tx-date">Date</label>
              <input
                id="tx-date"
                type="date"
                value={date}
                onChange={(e) => setDate(e.target.value)}
              />
            </div>
          )}
        </div>

        {type === 'transfer' && (
          <>
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
            <div className="field">
              <label htmlFor="tx-date-transfer">Date</label>
              <input
                id="tx-date-transfer"
                type="date"
                value={date}
                onChange={(e) => setDate(e.target.value)}
              />
            </div>
          </>
        )}

        {showDetails ? (
          <div className="grid-2">
            <div className="field">
              <label htmlFor="tx-payee">Bénéficiaire</label>
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
          </div>
        ) : (
          <button type="button" className="btn btn-ghost" onClick={() => setShowDetails(true)}>
            + Bénéficiaire ou note
          </button>
        )}

        {error && (
          <p className="pin-error" role="alert">
            {error}
          </p>
        )}

        <div className="sheet-actions">
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
