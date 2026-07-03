// Chiffrement au repos : clé AES-256-GCM dérivée du code PIN via PBKDF2.
// Sans le PIN, les données stockées dans IndexedDB sont illisibles.

const PBKDF2_ITERATIONS = 600_000

export function randomBytes(length: number): Uint8Array {
  const bytes = new Uint8Array(length)
  crypto.getRandomValues(bytes)
  return bytes
}

export async function deriveKey(pin: string, salt: Uint8Array): Promise<CryptoKey> {
  const material = await crypto.subtle.importKey(
    'raw',
    new TextEncoder().encode(pin),
    'PBKDF2',
    false,
    ['deriveKey'],
  )
  return crypto.subtle.deriveKey(
    {
      name: 'PBKDF2',
      salt: salt as BufferSource,
      iterations: PBKDF2_ITERATIONS,
      hash: 'SHA-256',
    },
    material,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt'],
  )
}

export interface EncryptedBlob {
  iv: Uint8Array
  data: ArrayBuffer
}

export async function encryptJson(key: CryptoKey, value: unknown): Promise<EncryptedBlob> {
  const iv = randomBytes(12)
  const plaintext = new TextEncoder().encode(JSON.stringify(value))
  const data = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv: iv as BufferSource },
    key,
    plaintext as BufferSource,
  )
  return { iv, data }
}

export async function decryptJson<T>(key: CryptoKey, blob: EncryptedBlob): Promise<T> {
  const plaintext = await crypto.subtle.decrypt(
    { name: 'AES-GCM', iv: blob.iv as BufferSource },
    key,
    blob.data,
  )
  return JSON.parse(new TextDecoder().decode(plaintext)) as T
}
