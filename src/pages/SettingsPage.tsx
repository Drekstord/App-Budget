import { useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { useStore } from '../store/useStore.ts'
import type { ThemePreference } from '../domain/types.ts'
import { backupFileName, BackupError, createBackup, parseBackup } from '../domain/backup.ts'
import { Modal } from '../components/Modal.tsx'

export function SettingsPage() {
  const data = useStore((s) => s.data)
  const updateSettings = useStore((s) => s.updateSettings)
  const changePin = useStore((s) => s.changePin)
  const importData = useStore((s) => s.importData)

  const fileRef = useRef<HTMLInputElement>(null)
  const [pinModalOpen, setPinModalOpen] = useState(false)
  const [currentPin, setCurrentPin] = useState('')
  const [newPin, setNewPin] = useState('')
  const [confirmPin, setConfirmPin] = useState('')
  const [pinMessage, setPinMessage] = useState('')
  const [importMessage, setImportMessage] = useState('')

  if (!data) return null
  const { settings } = data

  const exportBackup = () => {
    const backup = createBackup(data)
    const blob = new Blob([JSON.stringify(backup, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = backupFileName()
    a.click()
    URL.revokeObjectURL(url)
  }

  const onImportFile = async (file: File) => {
    setImportMessage('')
    try {
      const imported = parseBackup(await file.text())
      const count = imported.transactions.filter((t) => !t.deletedAt).length
      if (
        !window.confirm(
          `Restaurer cette sauvegarde (${count} opérations) ? Toutes les données actuelles seront remplacées.`,
        )
      ) {
        return
      }
      await importData(imported)
      setImportMessage('Sauvegarde restaurée avec succès. ✓')
    } catch (e) {
      setImportMessage(e instanceof BackupError ? e.message : 'Import impossible : fichier invalide.')
    }
  }

  const submitPinChange = async () => {
    setPinMessage('')
    if (!/^\d{4,8}$/.test(newPin)) {
      setPinMessage('Le nouveau code doit contenir 4 à 8 chiffres.')
      return
    }
    if (newPin !== confirmPin) {
      setPinMessage('Les deux nouveaux codes ne correspondent pas.')
      return
    }
    const ok = await changePin(currentPin, newPin)
    if (!ok) {
      setPinMessage('Code PIN actuel incorrect.')
      return
    }
    setPinModalOpen(false)
    setCurrentPin('')
    setNewPin('')
    setConfirmPin('')
  }

  return (
    <div className="stack">
      <section className="card">
        <h2>Apparence</h2>
        <div className="field">
          <label htmlFor="set-theme">Thème</label>
          <select
            id="set-theme"
            value={settings.theme}
            onChange={(e) => void updateSettings({ theme: e.target.value as ThemePreference })}
          >
            <option value="auto">Automatique (système)</option>
            <option value="light">Clair</option>
            <option value="dark">Sombre</option>
          </select>
        </div>
      </section>

      <section className="card">
        <h2>Budget</h2>
        <div className="field">
          <label htmlFor="set-start-day">Jour de début du mois budgétaire</label>
          <select
            id="set-start-day"
            value={settings.monthStartDay}
            onChange={(e) => void updateSettings({ monthStartDay: Number(e.target.value) })}
          >
            {Array.from({ length: 28 }, (_, i) => i + 1).map((d) => (
              <option key={d} value={d}>
                {d === 1 ? 'Le 1er (mois civil)' : `Le ${d}`}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="set-warn">Seuil d’alerte budget</label>
          <select
            id="set-warn"
            value={settings.warnThreshold}
            onChange={(e) => void updateSettings({ warnThreshold: Number(e.target.value) })}
          >
            {[50, 60, 70, 80, 90].map((p) => (
              <option key={p} value={p}>
                {p} % du budget
              </option>
            ))}
          </select>
        </div>
        <p>
          <Link to="/categories">Gérer les catégories →</Link>
        </p>
      </section>

      <section className="card">
        <h2>Alertes</h2>
        <div className="field">
          <label htmlFor="set-large">Alerte « grosse dépense » à partir de</label>
          <select
            id="set-large"
            value={settings.largeExpenseAlert}
            onChange={(e) => void updateSettings({ largeExpenseAlert: Number(e.target.value) })}
          >
            <option value={0}>Désactivée</option>
            <option value={5000}>50 €</option>
            <option value={10000}>100 €</option>
            <option value={20000}>200 €</option>
            <option value={50000}>500 €</option>
          </select>
        </div>
        <div className="field">
          <label>
            <input
              type="checkbox"
              style={{ width: 'auto', minHeight: 'auto', marginRight: '0.5rem' }}
              checked={settings.systemNotifications}
              onChange={(e) => {
                const enabled = e.target.checked
                if (enabled && typeof Notification !== 'undefined') {
                  void Notification.requestPermission().then((perm) => {
                    void updateSettings({ systemNotifications: perm === 'granted' })
                  })
                } else {
                  void updateSettings({ systemNotifications: false })
                }
              }}
            />
            Afficher aussi les alertes en notifications système
          </label>
          <p className="chart-note" style={{ marginTop: '0.35rem' }}>
            Les alertes (plafond de budget atteint, grosse dépense) s’affichent toujours dans
            l’application au moment de la saisie.
          </p>
        </div>
      </section>

      <section className="card">
        <h2>Sécurité</h2>
        <div className="field">
          <label htmlFor="set-lock">Verrouillage automatique</label>
          <select
            id="set-lock"
            value={settings.lockDelayMinutes}
            onChange={(e) => void updateSettings({ lockDelayMinutes: Number(e.target.value) })}
          >
            <option value={1}>Après 1 minute</option>
            <option value={5}>Après 5 minutes</option>
            <option value={15}>Après 15 minutes</option>
            <option value={60}>Après 1 heure</option>
            <option value={0}>Jamais</option>
          </select>
          <p className="chart-note" style={{ marginTop: '0.35rem' }}>
            Tant que ce délai n’est pas écoulé, rouvrir ou recharger l’application ne redemande
            pas le code PIN.
          </p>
        </div>
        <button type="button" className="btn" onClick={() => setPinModalOpen(true)}>
          Changer le code PIN
        </button>
      </section>

      <section className="card">
        <h2>Sauvegarde</h2>
        <p className="chart-note">
          Les données ne quittent jamais cet appareil. Exporte régulièrement une sauvegarde pour la
          transférer ou la conserver en lieu sûr.
        </p>
        <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap' }}>
          <button type="button" className="btn btn-primary" onClick={exportBackup}>
            Exporter (JSON)
          </button>
          <button type="button" className="btn" onClick={() => fileRef.current?.click()}>
            Importer une sauvegarde…
          </button>
          <input
            ref={fileRef}
            type="file"
            accept="application/json,.json"
            className="visually-hidden"
            aria-label="Fichier de sauvegarde à importer"
            onChange={(e) => {
              const file = e.target.files?.[0]
              if (file) void onImportFile(file)
              e.target.value = ''
            }}
          />
        </div>
        {importMessage && (
          <p role="status" style={{ marginBottom: 0 }}>
            {importMessage}
          </p>
        )}
      </section>

      <p className="chart-note" style={{ textAlign: 'center' }}>
        App Budget v1 — hors ligne, chiffrée, sans compte.
      </p>

      <Modal open={pinModalOpen} onClose={() => setPinModalOpen(false)} title="Changer le code PIN">
        <form
          onSubmit={(e) => {
            e.preventDefault()
            void submitPinChange()
          }}
        >
          <div className="field">
            <label htmlFor="pin-current">Code actuel</label>
            <input
              id="pin-current"
              type="password"
              inputMode="numeric"
              autoComplete="current-password"
              value={currentPin}
              onChange={(e) => setCurrentPin(e.target.value)}
            />
          </div>
          <div className="field">
            <label htmlFor="pin-new">Nouveau code (4 à 8 chiffres)</label>
            <input
              id="pin-new"
              type="password"
              inputMode="numeric"
              autoComplete="new-password"
              value={newPin}
              onChange={(e) => setNewPin(e.target.value)}
            />
          </div>
          <div className="field">
            <label htmlFor="pin-confirm">Confirme le nouveau code</label>
            <input
              id="pin-confirm"
              type="password"
              inputMode="numeric"
              autoComplete="new-password"
              value={confirmPin}
              onChange={(e) => setConfirmPin(e.target.value)}
            />
          </div>
          {pinMessage && (
            <p className="pin-error" role="alert">
              {pinMessage}
            </p>
          )}
          <button type="submit" className="btn btn-primary" style={{ width: '100%' }}>
            Changer le code
          </button>
        </form>
      </Modal>
    </div>
  )
}
