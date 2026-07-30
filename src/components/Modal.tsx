import { useEffect, useRef, type ReactNode } from 'react'
import { IconClose } from './icons.tsx'

interface ModalProps {
  open: boolean
  title: string
  onClose: () => void
  /** Commande secondaire placée dans l'en-tête (ex. scan d'un ticket). */
  action?: ReactNode
  children: ReactNode
}

/** Feuille modale accessible basée sur <dialog> (focus piégé et Échap natifs). */
export function Modal({ open, title, onClose, action, children }: ModalProps) {
  const ref = useRef<HTMLDialogElement>(null)

  useEffect(() => {
    const dialog = ref.current
    if (!dialog) return
    if (open && !dialog.open) dialog.showModal()
    if (!open && dialog.open) dialog.close()
  }, [open])

  return (
    <dialog
      ref={ref}
      className="modal"
      aria-label={title}
      onClose={onClose}
      onClick={(e) => {
        if (e.target === ref.current) onClose()
      }}
    >
      <div className="modal-inner">
        {/* Poignée : indique qu'on peut refermer la feuille en la faisant glisser. */}
        <div className="modal-grip" aria-hidden="true" />
        <div className="modal-header">
          <h2 style={{ margin: 0 }}>{title}</h2>
          <div className="modal-actions">
            {action}
            <button
              type="button"
              className="icon-btn icon-btn-bare"
              onClick={onClose}
              aria-label="Fermer"
            >
              <IconClose />
            </button>
          </div>
        </div>
        {open && children}
      </div>
    </dialog>
  )
}
