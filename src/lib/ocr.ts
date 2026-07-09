// OCR de tickets de caisse, 100 % local : moteur Tesseract (WASM) et langue
// française auto-hébergés dans /ocr — l'image ne quitte jamais l'appareil.

export type OcrProgress = (progress: number) => void

const base = new URL(import.meta.env.BASE_URL, window.location.href).href

export async function recognizeReceipt(image: File | Blob, onProgress?: OcrProgress): Promise<string> {
  const { createWorker } = await import('tesseract.js')
  const worker = await createWorker('fra', 1, {
    workerPath: `${base}ocr/worker.min.js`,
    corePath: `${base}ocr`,
    langPath: `${base}ocr/lang`,
    gzip: true,
    logger: (m) => {
      if (m.status === 'recognizing text' && onProgress) onProgress(m.progress)
    },
  })
  try {
    const { data } = await worker.recognize(image)
    return data.text
  } finally {
    await worker.terminate()
  }
}
