import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useStore } from '../store/useStore.ts'
import { alive, type Category, type CategoryKind } from '../domain/types.ts'
import { CATEGORICAL } from '../theme.ts'
import { Modal } from '../components/Modal.tsx'
import { IconEdit, IconPlus } from '../components/icons.tsx'

export function CategoriesPage() {
  const data = useStore((s) => s.data)
  const addCategory = useStore((s) => s.addCategory)
  const updateCategory = useStore((s) => s.updateCategory)
  const deleteCategory = useStore((s) => s.deleteCategory)

  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<Category | null>(null)
  const [name, setName] = useState('')
  const [icon, setIcon] = useState('🏷️')
  const [kind, setKind] = useState<CategoryKind>('expense')
  const [parentId, setParentId] = useState('')
  const [colorSlot, setColorSlot] = useState(1)
  const [reassignTo, setReassignTo] = useState('')
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [error, setError] = useState('')

  if (!data) return null
  const categories = alive(data.categories)
  const byKind = (k: CategoryKind) => categories.filter((c) => c.kind === k)
  const roots = (k: CategoryKind) => byKind(k).filter((c) => !c.parentId)
  const childrenOf = (id: string) => categories.filter((c) => c.parentId === id)

  const openForm = (category: Category | null, defaultKind: CategoryKind = 'expense') => {
    setEditing(category)
    setName(category?.name ?? '')
    setIcon(category?.icon ?? '🏷️')
    setKind(category?.kind ?? defaultKind)
    setParentId(category?.parentId ?? '')
    setColorSlot(category?.colorSlot ?? 1)
    setConfirmDelete(false)
    setReassignTo('')
    setError('')
    setOpen(true)
  }

  const save = async () => {
    if (!name.trim()) {
      setError('Donne un nom à la catégorie.')
      return
    }
    const payload = {
      name: name.trim(),
      icon: icon.trim() || '🏷️',
      kind,
      parentId: parentId || null,
      colorSlot,
    }
    if (editing) await updateCategory(editing.id, payload)
    else await addCategory(payload)
    setOpen(false)
  }

  const remove = async () => {
    if (!editing) return
    await deleteCategory(editing.id, reassignTo || null)
    setOpen(false)
  }

  const usedByTransactions = editing
    ? data.transactions.some((t) => !t.deletedAt && t.categoryId === editing.id)
    : false

  const renderGroup = (title: string, k: CategoryKind) => (
    <section className="card">
      <span className="label" style={{ marginBottom: '0.2rem' }}>
        {title}
      </span>
      <ul className="rows">
        {roots(k).flatMap((c) =>
          [c, ...childrenOf(c.id)].map((cat, i) => (
            <li key={cat.id} className="row" style={{ padding: 0 }}>
              <button
                type="button"
                className="row-btn"
                aria-label={`Modifier la catégorie ${cat.name}`}
                onClick={() => openForm(cat)}
                style={i > 0 ? { paddingLeft: '1.9rem' } : undefined}
              >
                <span className="glyph glyph-sm" aria-hidden="true">
                  {cat.icon}
                </span>
                <span className="row-main">
                  <span className="row-title">{cat.name}</span>
                </span>
                <span
                  className="dot"
                  style={{ background: CATEGORICAL.light[(cat.colorSlot - 1) % 8] }}
                  aria-hidden="true"
                />
                <IconEdit className="chev" size={16} />
              </button>
            </li>
          )),
        )}
      </ul>
      <button
        type="button"
        className="btn btn-sm"
        style={{ marginTop: '0.6rem' }}
        onClick={() => openForm(null, k)}
      >
        <IconPlus size={16} /> Ajouter
      </button>
    </section>
  )

  return (
    <>
      <p style={{ margin: 0 }}>
        <Link to="/plus">← Retour</Link>
      </p>
      {renderGroup('Catégories de dépenses', 'expense')}
      {renderGroup('Catégories de revenus', 'income')}

      <Modal
        open={open}
        onClose={() => setOpen(false)}
        title={editing ? 'Modifier la catégorie' : 'Nouvelle catégorie'}
      >
        <form
          onSubmit={(e) => {
            e.preventDefault()
            void save()
          }}
        >
          <div className="field">
            <label htmlFor="cat-name">Nom</label>
            <input id="cat-name" value={name} onChange={(e) => setName(e.target.value)} autoFocus />
          </div>
          <div className="grid-2">
            <div className="field">
              <label htmlFor="cat-icon">Icône (émoji)</label>
              <input id="cat-icon" value={icon} onChange={(e) => setIcon(e.target.value)} maxLength={4} />
            </div>
            <div className="field">
              <label htmlFor="cat-kind">Type</label>
              <select
                id="cat-kind"
                value={kind}
                onChange={(e) => setKind(e.target.value as CategoryKind)}
                disabled={Boolean(editing)}
              >
                <option value="expense">Dépense</option>
                <option value="income">Revenu</option>
              </select>
            </div>
          </div>
          <div className="field">
            <label htmlFor="cat-parent">Catégorie parente (optionnel)</label>
            <select id="cat-parent" value={parentId} onChange={(e) => setParentId(e.target.value)}>
              <option value="">— Aucune —</option>
              {roots(kind)
                .filter((c) => c.id !== editing?.id)
                .map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                  </option>
                ))}
            </select>
          </div>
          <div className="field">
            <span className="field-label" id="cat-color-label">
              Couleur (graphiques)
            </span>
            <div className="chip-row" role="group" aria-labelledby="cat-color-label">
              {CATEGORICAL.light.map((hex, i) => (
                <button
                  key={hex}
                  type="button"
                  className="chip"
                  aria-pressed={colorSlot === i + 1}
                  aria-label={`Couleur ${i + 1}`}
                  onClick={() => setColorSlot(i + 1)}
                  style={{ minWidth: 44 }}
                >
                  <span
                    aria-hidden="true"
                    style={{
                      display: 'inline-block',
                      width: 18,
                      height: 18,
                      borderRadius: 5,
                      background: hex,
                      verticalAlign: '-3px',
                    }}
                  />
                </button>
              ))}
            </div>
          </div>

          {error && (
            <p className="pin-error" role="alert">
              {error}
            </p>
          )}

          {editing && !confirmDelete && (
            <button type="button" className="btn btn-danger" onClick={() => setConfirmDelete(true)}>
              Supprimer cette catégorie…
            </button>
          )}
          {editing && confirmDelete && (
            <div className="notice notice-warning" style={{ display: 'block', margin: '0.75rem 0' }}>
              {usedByTransactions ? (
                <>
                  <p style={{ marginTop: 0 }}>
                    Des opérations utilisent cette catégorie. Vers quelle catégorie les réaffecter ?
                  </p>
                  <select
                    aria-label="Catégorie de réaffectation"
                    value={reassignTo}
                    onChange={(e) => setReassignTo(e.target.value)}
                  >
                    <option value="">Sans catégorie</option>
                    {byKind(kind)
                      .filter((c) => c.id !== editing.id)
                      .map((c) => (
                        <option key={c.id} value={c.id}>
                          {c.name}
                        </option>
                      ))}
                  </select>
                </>
              ) : (
                <p style={{ marginTop: 0 }}>Confirmer la suppression ?</p>
              )}
              <button
                type="button"
                className="btn btn-danger"
                style={{ marginTop: '0.6rem' }}
                onClick={() => void remove()}
              >
                Supprimer définitivement
              </button>
            </div>
          )}

          <div className="sheet-actions">
            <button type="submit" className="btn btn-primary" style={{ flex: 1 }}>
              Enregistrer
            </button>
          </div>
        </form>
      </Modal>
    </>
  )
}
