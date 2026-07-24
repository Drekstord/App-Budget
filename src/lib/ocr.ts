// OCR de tickets de caisse, 100 % local : moteur Tesseract (WASM) et langue
// française auto-hébergés dans /ocr — l'image ne quitte jamais l'appareil.

export type OcrProgress = (progress: number) => void

export class OcrError extends Error {}

const base = new URL(import.meta.env.BASE_URL, window.location.href).href

/** Taille max (px) du plus grand côté envoyé à l'OCR. */
const MAX_DIMENSION = 2000
/** Délai maximal avant d'abandonner la reconnaissance. */
const TIMEOUT_MS = 90_000

/**
 * Prépare la photo avant l'OCR : réorientation EXIF, réduction de taille et
 * ré-encodage. Indispensable sur mobile où les photos font plusieurs dizaines
 * de méga-pixels — sans ça l'OCR sature la mémoire ou traîne indéfiniment.
 */
async function preprocess(image: File | Blob): Promise<Blob> {
  let bitmap: ImageBitmap
  try {
    bitmap = await createImageBitmap(image, { imageOrientation: 'from-image' })
  } catch {
    // Format non décodable par le navigateur (HEIC…) ou createImageBitmap absent.
    return image
  }
  try {
    const scale = Math.min(1, MAX_DIMENSION / Math.max(bitmap.width, bitmap.height))
    const width = Math.max(1, Math.round(bitmap.width * scale))
    const height = Math.max(1, Math.round(bitmap.height * scale))
    const canvas = document.createElement('canvas')
    canvas.width = width
    canvas.height = height
    const ctx = canvas.getContext('2d')
    if (!ctx) return image
    ctx.drawImage(bitmap, 0, 0, width, height)
    const blob = await new Promise<Blob | null>((resolve) =>
      canvas.toBlob((b) => resolve(b), 'image/jpeg', 0.9),
    )
    return blob ?? image
  } finally {
    bitmap.close()
  }
}

export async function recognizeReceipt(image: File | Blob, onProgress?: OcrProgress): Promise<string> {
  const prepared = await preprocess(image)
  const { createWorker } = await import('tesseract.js')

  let worker: Awaited<ReturnType<typeof createWorker>>
  try {
    worker = await createWorker('fra', 1, {
      workerPath: `${base}ocr/worker.min.js`,
      corePath: `${base}ocr`,
      langPath: `${base}ocr/lang`,
      gzip: true,
      logger: (m) => {
        if (m.status === 'recognizing text' && onProgress) onProgress(m.progress)
      },
    })
  } catch {
    // Échec de chargement du moteur (réseau au 1er scan, stockage, etc.).
    throw new OcrError('engine')
  }

  try {
    const recognition = worker.recognize(prepared)
    const timeout = new Promise<never>((_, reject) =>
      setTimeout(() => reject(new OcrError('timeout')), TIMEOUT_MS),
    )
    const { data } = await Promise.race([recognition, timeout])
    return data.text
  } catch (e) {
    if (e instanceof OcrError) throw e
    throw new OcrError('recognition')
  } finally {
    await worker.terminate().catch(() => {})
  }
}
