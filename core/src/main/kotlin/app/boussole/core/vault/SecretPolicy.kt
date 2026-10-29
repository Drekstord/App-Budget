package app.boussole.core.vault

/**
 * Règles du secret d'ouverture : un code d'au moins 6 chiffres ou un mot de
 * passe d'au moins 12 caractères (recommandé, via Proton Pass).
 */
object SecretPolicy {
    const val MIN_PIN_DIGITS = 6
    const val MIN_PASSWORD_CHARS = 12

    enum class Verdict { OK, PIN_TOO_SHORT, PIN_TOO_SIMPLE, PASSWORD_TOO_SHORT }

    fun check(secret: CharSequence): Verdict {
        val isPin = secret.isNotEmpty() && secret.all { it in '0'..'9' }
        return when {
            isPin && secret.length < MIN_PIN_DIGITS -> Verdict.PIN_TOO_SHORT
            isPin && isTrivialPin(secret) -> Verdict.PIN_TOO_SIMPLE
            isPin -> Verdict.OK
            secret.codePoints().count() < MIN_PASSWORD_CHARS -> Verdict.PASSWORD_TOO_SHORT
            else -> Verdict.OK
        }
    }

    /** Chiffres tous identiques ou suite régulière (123456, 975310 n'en est pas une). */
    private fun isTrivialPin(pin: CharSequence): Boolean {
        val digits = pin.map { it - '0' }
        val steps = digits.zipWithNext { a, b -> b - a }.toSet()
        return steps.size == 1 && steps.first() in -1..1
    }
}

/**
 * Délai imposé après des échecs de mot de passe consécutifs. C'est une défense
 * en profondeur : la vraie barrière est la clé matérielle, qui exige l'empreinte.
 */
object AttemptThrottle {
    const val FREE_ATTEMPTS = 5
    private const val BASE_MILLIS = 30_000L
    private const val MAX_MILLIS = 24 * 3_600_000L

    fun lockoutMillis(consecutiveFailures: Int): Long {
        if (consecutiveFailures < FREE_ATTEMPTS) return 0
        val exponent = (consecutiveFailures - FREE_ATTEMPTS).coerceAtMost(20)
        return (BASE_MILLIS shl exponent).coerceAtMost(MAX_MILLIS)
    }
}
