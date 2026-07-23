// Éditeur de lignes de flux (revenus futurs ou événements de dépense) pour un
// plan de financement. Les montants sont saisis en texte et convertis à la
// sauvegarde par la page parente.

import type { FundingRecurrence } from '../domain/types.ts'

export interface FlowDraft {
  id: string
  label: string
  amount: string
  date: string
  recurrence: FundingRecurrence
  kind: 'fixed' | 'variable'
}

interface FlowRowsProps {
  rows: FlowDraft[]
  onChange: (rows: FlowDraft[]) => void
  /** Les revenus proposent fixe/variable ; les dépenses non. */
  showKind: boolean
  addLabel: string
  defaultDate: string
}

export function FlowRows({ rows, onChange, showKind, addLabel, defaultDate }: FlowRowsProps) {
  const update = (id: string, patch: Partial<FlowDraft>) => {
    onChange(rows.map((r) => (r.id === id ? { ...r, ...patch } : r)))
  }
  const remove = (id: string) => onChange(rows.filter((r) => r.id !== id))
  const add = () => {
    onChange([
      ...rows,
      {
        id: crypto.randomUUID(),
        label: '',
        amount: '',
        date: defaultDate,
        recurrence: 'monthly',
        kind: 'fixed',
      },
    ])
  }

  return (
    <div className="flow-rows">
      {rows.map((row) => (
        <div key={row.id} className="flow-row card">
          <div className="field" style={{ margin: 0 }}>
            <label htmlFor={`fl-label-${row.id}`}>Libellé</label>
            <input
              id={`fl-label-${row.id}`}
              value={row.label}
              onChange={(e) => update(row.id, { label: e.target.value })}
              placeholder={showKind ? 'Salaire, prime…' : 'Loyer, vacances…'}
            />
          </div>
          <div className="grid-2">
            <div className="field" style={{ margin: 0 }}>
              <label htmlFor={`fl-amount-${row.id}`}>Montant (€)</label>
              <input
                id={`fl-amount-${row.id}`}
                inputMode="decimal"
                value={row.amount}
                onChange={(e) => update(row.id, { amount: e.target.value })}
                placeholder="0,00"
              />
            </div>
            <div className="field" style={{ margin: 0 }}>
              <label htmlFor={`fl-date-${row.id}`}>
                {row.recurrence === 'once' ? 'Date' : 'À partir du'}
              </label>
              <input
                id={`fl-date-${row.id}`}
                type="date"
                value={row.date}
                onChange={(e) => update(row.id, { date: e.target.value })}
              />
            </div>
          </div>
          <div className="grid-2">
            <div className="field" style={{ margin: 0 }}>
              <label htmlFor={`fl-rec-${row.id}`}>Fréquence</label>
              <select
                id={`fl-rec-${row.id}`}
                value={row.recurrence}
                onChange={(e) => update(row.id, { recurrence: e.target.value as FundingRecurrence })}
              >
                <option value="monthly">Chaque mois</option>
                <option value="yearly">Chaque année</option>
                <option value="once">Une seule fois</option>
              </select>
            </div>
            {showKind && (
              <div className="field" style={{ margin: 0 }}>
                <label htmlFor={`fl-kind-${row.id}`}>Type</label>
                <select
                  id={`fl-kind-${row.id}`}
                  value={row.kind}
                  onChange={(e) =>
                    update(row.id, { kind: e.target.value as 'fixed' | 'variable' })
                  }
                >
                  <option value="fixed">Fixe (garanti)</option>
                  <option value="variable">Variable (bonus)</option>
                </select>
              </div>
            )}
          </div>
          <button
            type="button"
            className="btn btn-ghost"
            onClick={() => remove(row.id)}
            aria-label={`Retirer ${row.label || 'cette ligne'}`}
          >
            Retirer
          </button>
        </div>
      ))}
      <button type="button" className="btn" onClick={add}>
        + {addLabel}
      </button>
    </div>
  )
}
