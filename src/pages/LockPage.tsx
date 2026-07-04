import { useState } from 'react'
import { useStore } from '../store/useStore.ts'

const MIN_PIN = 4
const MAX_PIN = 8

interface PinPadProps {
  value: string
  onChange: (next: string) => void
  onValidate: () => void
  validateDisabled: boolean
}

function PinPad({ value, onChange, onValidate, validateDisabled }: PinPadProps) {
  const press = (digit: string) => {
    if (value.length < MAX_PIN) onChange(value + digit)
  }
  return (
    <div className="pin-pad" role="group" aria-label="Pavé numérique">
      {['1', '2', '3', '4', '5', '6', '7', '8', '9'].map((d) => (
        <button key={d} type="button" onClick={() => press(d)}>
          {d}
        </button>
      ))}
      <button
        type="button"
        onClick={() => onChange(value.slice(0, -1))}
        aria-label="Effacer le dernier chiffre"
      >
        ⌫
      </button>
      <button type="button" onClick={() => press('0')}>
        0
      </button>
      <button
        type="button"
        onClick={onValidate}
        disabled={validateDisabled}
        aria-label="Valider le code"
        style={{ fontWeight: 700 }}
      >
        OK
      </button>
    </div>
  )
}

function PinDots({ length }: { length: number }) {
  return (
    <div className="pin-dots" aria-hidden="true">
      {Array.from({ length: MAX_PIN }, (_, i) => (
        <span key={i} className={i < length ? 'filled' : ''} style={i >= MIN_PIN && i >= length ? { opacity: 0.35 } : undefined} />
      ))}
    </div>
  )
}

export function LockPage({ mode }: { mode: 'setup' | 'locked' }) {
  const setupPin = useStore((s) => s.setupPin)
  const unlock = useStore((s) => s.unlock)
  const [pin, setPin] = useState('')
  const [firstPin, setFirstPin] = useState<string | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const isSetup = mode === 'setup'
  const isConfirm = isSetup && firstPin !== null

  const title = !isSetup
    ? 'Saisis ton code PIN'
    : isConfirm
      ? 'Confirme ton code PIN'
      : 'Choisis un code PIN'

  const validate = async () => {
    if (pin.length < MIN_PIN || busy) return
    setError('')
    if (isSetup && !isConfirm) {
      setFirstPin(pin)
      setPin('')
      return
    }
    setBusy(true)
    try {
      if (isSetup) {
        if (pin !== firstPin) {
          setError('Les deux codes ne correspondent pas.')
          setFirstPin(null)
          setPin('')
          return
        }
        await setupPin(pin)
      } else {
        const ok = await unlock(pin)
        if (!ok) {
          setError('Code PIN incorrect.')
          setPin('')
        }
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="lock-screen">
      <div style={{ fontSize: '3rem' }} aria-hidden="true">
        🔐
      </div>
      <div>
        <h1>{title}</h1>
        {isSetup && !isConfirm && (
          <p style={{ color: 'var(--text-2)', maxWidth: 320 }}>
            De {MIN_PIN} à {MAX_PIN} chiffres. Il chiffre toutes tes données : sans lui, elles sont
            irrécupérables.
          </p>
        )}
      </div>
      <PinDots length={pin.length} />
      <p className="pin-error" role="alert">
        {busy ? 'Vérification…' : error}
      </p>
      <PinPad
        value={pin}
        onChange={setPin}
        onValidate={() => void validate()}
        validateDisabled={pin.length < MIN_PIN || busy}
      />
    </div>
  )
}
