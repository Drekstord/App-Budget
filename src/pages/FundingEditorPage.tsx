import { useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useStore } from '../store/useStore.ts'
import { alive, type FundingAccountRule, type FundingFlow } from '../domain/types.ts'
import { centsToInput, formatEUR, parseAmountToCents } from '../domain/money.ts'
import { accountBalance } from '../domain/stats.ts'
import { todayISO } from '../domain/periods.ts'
import { FlowRows, type FlowDraft } from '../components/FlowRows.tsx'

interface RuleDraft {
  accountId: string
  included: boolean
  keepMin: string
  excluded: boolean
  useOverdraft: boolean
}

function toFlowDraft(f: FundingFlow): FlowDraft {
  return {
    id: f.id,
    label: f.label,
    amount: centsToInput(f.amount),
    date: f.date,
    recurrence: f.recurrence,
    kind: f.kind,
  }
}

/** Convertit les brouillons en flux valides, en ignorant les lignes vides. */
function fromFlowDrafts(rows: FlowDraft[], defaultKind: 'fixed' | 'variable'): FundingFlow[] {
  const flows: FundingFlow[] = []
  for (const r of rows) {
    const amount = parseAmountToCents(r.amount)
    if (amount === null || amount <= 0 || !r.date) continue
    flows.push({
      id: r.id,
      label: r.label.trim() || (defaultKind === 'fixed' ? 'Revenu' : 'Dépense'),
      amount,
      date: r.date,
      recurrence: r.recurrence,
      kind: r.kind,
    })
  }
  return flows
}

export function FundingEditorPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const data = useStore((s) => s.data)
  const addFundingPlan = useStore((s) => s.addFundingPlan)
  const updateFundingPlan = useStore((s) => s.updateFundingPlan)

  const existing = id ? data?.fundingPlans.find((p) => p.id === id && !p.deletedAt) : undefined
  const accounts = useMemo(() => (data ? alive(data.accounts).filter((a) => !a.archived) : []), [data])

  const [name, setName] = useState(existing?.name ?? '')
  const [targetLabel, setTargetLabel] = useState(existing?.targetLabel ?? '')
  const [targetAmount, setTargetAmount] = useState(
    existing ? centsToInput(existing.targetAmount) : '',
  )
  const [targetDate, setTargetDate] = useState(existing?.targetDate ?? '')

  // Règles de comptes, dans l'ordre de priorité. Les comptes non encore réglés
  // sont ajoutés à la fin, inclus par défaut.
  const [rules, setRules] = useState<RuleDraft[]>(() => {
    const existingRules = new Map(existing?.accountRules.map((r) => [r.accountId, r]) ?? [])
    const ordered = [...(existing?.accountRules ?? [])]
      .sort((a, b) => a.priority - b.priority)
      .map((r) => ({
        accountId: r.accountId,
        included: true,
        keepMin: r.keepMin ? centsToInput(r.keepMin) : '',
        excluded: r.excluded,
        useOverdraft: r.useOverdraft !== false,
      }))
    const rest = accounts
      .filter((a) => !existingRules.has(a.id))
      .map((a) => ({
        accountId: a.id,
        included: !existing,
        keepMin: '',
        excluded: false,
        useOverdraft: true,
      }))
    return [...ordered, ...rest]
  })

  const [incomes, setIncomes] = useState<FlowDraft[]>(
    () => existing?.incomes.map(toFlowDraft) ?? [],
  )
  const [expenseEvents, setExpenseEvents] = useState<FlowDraft[]>(
    () => existing?.expenseEvents.map(toFlowDraft) ?? [],
  )
  const [error, setError] = useState('')

  if (!data) return null
  const accountById = new Map(accounts.map((a) => [a.id, a]))

  const move = (index: number, delta: number) => {
    const next = [...rules]
    const target = index + delta
    if (target < 0 || target >= next.length) return
    ;[next[index], next[target]] = [next[target], next[index]]
    setRules(next)
  }
  const patchRule = (accountId: string, patch: Partial<RuleDraft>) => {
    setRules((rs) => rs.map((r) => (r.accountId === accountId ? { ...r, ...patch } : r)))
  }

  const save = async () => {
    const amount = parseAmountToCents(targetAmount)
    if (!targetLabel.trim()) return setError('Indique le motif de la dépense.')
    if (amount === null || amount <= 0) return setError('Indique le montant de la dépense.')
    if (!targetDate) return setError('Indique la date prévue de la dépense.')

    const accountRules: FundingAccountRule[] = rules
      .filter((r) => r.included)
      .map((r, i) => ({
        accountId: r.accountId,
        priority: i,
        keepMin: r.excluded ? 0 : (parseAmountToCents(r.keepMin) ?? 0),
        excluded: r.excluded,
        useOverdraft: r.useOverdraft,
      }))

    const input = {
      name: name.trim() || targetLabel.trim(),
      targetLabel: targetLabel.trim(),
      targetAmount: amount,
      targetDate,
      accountRules,
      incomes: fromFlowDrafts(incomes, 'fixed'),
      expenseEvents: fromFlowDrafts(expenseEvents, 'variable').map((f) => ({ ...f, kind: 'fixed' as const })),
    }

    if (existing) {
      await updateFundingPlan(existing.id, input)
      navigate(`/plans/${existing.id}`)
    } else {
      const plan = await addFundingPlan(input)
      navigate(`/plans/${plan.id}`)
    }
  }

  return (
    <div className="stack">
      <form
        onSubmit={(e) => {
          e.preventDefault()
          void save()
        }}
        className="stack"
      >
        <section className="card">
          <h2>La dépense à financer</h2>
          <div className="field">
            <label htmlFor="fp-label">Motif</label>
            <input
              id="fp-label"
              value={targetLabel}
              onChange={(e) => setTargetLabel(e.target.value)}
              placeholder="Voiture, travaux, mariage…"
            />
          </div>
          <div className="grid-2">
            <div className="field">
              <label htmlFor="fp-amount">Montant (€)</label>
              <input
                id="fp-amount"
                inputMode="decimal"
                value={targetAmount}
                onChange={(e) => setTargetAmount(e.target.value)}
                placeholder="8000"
              />
            </div>
            <div className="field">
              <label htmlFor="fp-date">Prévue le</label>
              <input
                id="fp-date"
                type="date"
                value={targetDate}
                onChange={(e) => setTargetDate(e.target.value)}
              />
            </div>
          </div>
          <div className="field" style={{ marginBottom: 0 }}>
            <label htmlFor="fp-name">Nom du plan (optionnel)</label>
            <input
              id="fp-name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="Par défaut : le motif"
            />
          </div>
        </section>

        <section className="card">
          <h2>Mes comptes et priorités</h2>
          <p className="chart-note">
            L’ordre indique dans quel compte piocher en premier. Protège une épargne en fixant un
            montant à préserver, ou en cochant « ne pas toucher ».
          </p>
          <ul className="rows">
            {rules.map((rule, index) => {
              const account = accountById.get(rule.accountId)
              if (!account) return null
              return (
                <li key={rule.accountId} className="rule-row">
                  <div className="rule-head">
                    <label className="field-inline">
                      <input
                        type="checkbox"
                        checked={rule.included}
                        onChange={(e) => patchRule(rule.accountId, { included: e.target.checked })}
                        aria-label={`Inclure ${account.name} dans le plan`}
                      />
                      <span aria-hidden="true">{account.icon}</span>
                      <span>
                        <strong>{account.name}</strong>
                        <br />
                        <span className="row-meta">
                          {formatEUR(accountBalance(account, data.transactions))}
                        </span>
                      </span>
                    </label>
                    {rule.included && (
                      <span className="rule-move">
                        <button
                          type="button"
                          className="icon-btn"
                          onClick={() => move(index, -1)}
                          disabled={index === 0}
                          aria-label={`Monter ${account.name} dans les priorités`}
                        >
                          ↑
                        </button>
                        <button
                          type="button"
                          className="icon-btn"
                          onClick={() => move(index, 1)}
                          disabled={index === rules.length - 1}
                          aria-label={`Descendre ${account.name} dans les priorités`}
                        >
                          ↓
                        </button>
                      </span>
                    )}
                  </div>
                  {rule.included && (
                    <div className="rule-body">
                      <label className="field-inline">
                        <input
                          type="checkbox"
                          checked={rule.excluded}
                          onChange={(e) => patchRule(rule.accountId, { excluded: e.target.checked })}
                        />
                        Ne pas toucher à ce compte
                      </label>
                      {!rule.excluded && (
                        <div className="field" style={{ margin: '0.5rem 0 0' }}>
                          <label htmlFor={`keep-${rule.accountId}`}>À préserver (€)</label>
                          <input
                            id={`keep-${rule.accountId}`}
                            inputMode="decimal"
                            value={rule.keepMin}
                            onChange={(e) => patchRule(rule.accountId, { keepMin: e.target.value })}
                            placeholder="0"
                          />
                        </div>
                      )}
                      {!rule.excluded && (account.overdraft ?? 0) > 0 && (
                        <label className="field-inline" style={{ marginTop: '0.5rem' }}>
                          <input
                            type="checkbox"
                            checked={rule.useOverdraft}
                            onChange={(e) =>
                              patchRule(rule.accountId, { useOverdraft: e.target.checked })
                            }
                          />
                          Utiliser le découvert autorisé ({formatEUR(account.overdraft)})
                        </label>
                      )}
                    </div>
                  )}
                </li>
              )
            })}
          </ul>
        </section>

        <section className="card">
          <h2>Revenus futurs</h2>
          <p className="chart-note">
            Salaire, primes… Marque en « variable » ce qui n’est pas garanti : le plan le comptera
            comme un bonus, jamais comme un acquis.
          </p>
          <FlowRows
            rows={incomes}
            onChange={setIncomes}
            showKind
            addLabel="Ajouter un revenu"
            defaultDate={todayISO()}
          />
        </section>

        <section className="card">
          <h2>Dépenses prévues d’ici là</h2>
          <p className="chart-note">
            Les autres sorties d’argent à venir (loyer, vacances…) qui réduisent ce que tu pourras
            mettre de côté.
          </p>
          <FlowRows
            rows={expenseEvents}
            onChange={setExpenseEvents}
            showKind={false}
            addLabel="Ajouter une dépense"
            defaultDate={todayISO()}
          />
        </section>

        {error && (
          <p className="pin-error" role="alert">
            {error}
          </p>
        )}
        <div style={{ display: 'flex', gap: '0.5rem' }}>
          <button
            type="button"
            className="btn"
            onClick={() => navigate(existing ? `/plans/${existing.id}` : '/plans')}
          >
            Annuler
          </button>
          <button type="submit" className="btn btn-primary" style={{ flex: 1 }}>
            {existing ? 'Enregistrer' : 'Créer le plan'}
          </button>
        </div>
      </form>
    </div>
  )
}
