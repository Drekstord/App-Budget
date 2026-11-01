package app.boussole.core.domain

import app.boussole.core.crypto.Base64Serializer
import kotlinx.serialization.Serializable

// Tout le contenu du coffre. Chaque champ ajouté après la version 1 porte une
// valeur par défaut : un coffre plus ancien se relit sans migration manuelle,
// puis [VaultData.normalized] complète ce qui manque (catégories par défaut…).

@Serializable
data class VaultData(
    val schemaVersion: Int = SCHEMA_VERSION,
    val accounts: List<Account> = emptyList(),
    val transactions: List<Transaction> = emptyList(),
    val categories: List<Category> = emptyList(),
    val envelopes: List<Envelope> = emptyList(),
    val envelopeMoves: List<EnvelopeMove> = emptyList(),
    val rules: List<CategoryRule> = emptyList(),
    val fixedCharges: List<FixedCharge> = emptyList(),
    val goals: List<Goal> = emptyList(),
    val profile: LifeProfile = LifeProfile(),
    val settings: AppSettings = AppSettings(),
    /** Clé privée X25519 de la boîte de réception des captures (cf. capture/Inbox). */
    @Serializable(with = Base64Serializer::class) val inboxPrivateKey: ByteArray = ByteArray(0),
    /**
     * Saisies en cours (formulaires non validés), pour les retrouver après un
     * verrouillage : chiffrées comme le reste du coffre.
     */
    val drafts: Map<String, String> = emptyMap(),
) {
    fun category(id: String?): Category? = id?.let { wanted -> categories.firstOrNull { it.id == wanted } }

    fun account(id: String?): Account? = id?.let { wanted -> accounts.firstOrNull { it.id == wanted } }

    /** Complète un coffre ancien ou neuf : catégories par défaut, version courante. */
    fun normalized(): VaultData {
        val existing = categories.map { it.id }.toSet()
        val missing = Defaults.categories.filter { it.id !in existing }
        return copy(schemaVersion = SCHEMA_VERSION, categories = categories + missing)
    }

    // Égalité structurelle (la clé privée est un tableau).
    override fun equals(other: Any?): Boolean =
        other is VaultData && schemaVersion == other.schemaVersion && accounts == other.accounts &&
            transactions == other.transactions && categories == other.categories &&
            envelopes == other.envelopes && envelopeMoves == other.envelopeMoves && rules == other.rules &&
            fixedCharges == other.fixedCharges && goals == other.goals && profile == other.profile &&
            settings == other.settings && inboxPrivateKey.contentEquals(other.inboxPrivateKey) && drafts == other.drafts

    override fun hashCode(): Int = transactions.hashCode()

    companion object {
        const val SCHEMA_VERSION = 2
    }
}

// --- Comptes ------------------------------------------------------------------

@Serializable
enum class AccountKind { CHECKING, SAVINGS, CASH }

/**
 * Produit bancaire précis. [illiquid] : argent qu'on ne retire pas sans casser
 * le produit (PEL, assurance vie, PEA…) ; les conseils ne le mobilisent jamais.
 */
@Serializable
enum class AccountProduct(val label: String, val kind: AccountKind, val illiquid: Boolean) {
    COMPTE_COURANT("Compte courant", AccountKind.CHECKING, false),
    LIVRET_A("Livret A", AccountKind.SAVINGS, false),
    LDDS("LDDS", AccountKind.SAVINGS, false),
    LEP("LEP", AccountKind.SAVINGS, false),
    LIVRET_JEUNE("Livret jeune", AccountKind.SAVINGS, false),
    LIVRET_BANCAIRE("Livret bancaire", AccountKind.SAVINGS, false),
    CEL("CEL", AccountKind.SAVINGS, false),
    PEL("PEL", AccountKind.SAVINGS, true),
    ASSURANCE_VIE("Assurance vie", AccountKind.SAVINGS, true),
    PEA("PEA", AccountKind.SAVINGS, true),
    COMPTE_TITRES("Compte-titres", AccountKind.SAVINGS, true),
    ESPECES("Espèces", AccountKind.CASH, false),
    AUTRE("Autre", AccountKind.SAVINGS, false),
}

@Serializable
data class Account(
    val id: String,
    val name: String,
    val kind: AccountKind,
    val institution: String,
    /** Solde connu à [openingDate] (centimes, peut être négatif). */
    val openingBalanceCents: Long = 0,
    /** AAAA-MM-JJ ; null tant que l'utilisateur n'a pas saisi de solde. */
    val openingDate: String? = null,
    /** Instant ISO de la saisie du solde : départage les opérations du même jour. */
    val openingSetAt: String? = null,
    /** Découvert autorisé, en centimes positifs. */
    val overdraftLimitCents: Long = 0,
    /** Épargne que les conseils ne doivent jamais proposer d'utiliser. */
    val protected: Boolean = false,
    /** Taux annuel du découvert (agios) ou de l'épargne, en pour cent. */
    val ratePercent: Double = 0.0,
    /** Produit précis (Livret A, PEL…) ; null pour les comptes créés avant. */
    val product: AccountProduct? = null,
    /** Compte fermé : gardé pour l'historique, plus proposé. */
    val archived: Boolean = false,
) {
    val effectiveProduct: AccountProduct
        get() = product ?: when (kind) {
            AccountKind.CHECKING -> AccountProduct.COMPTE_COURANT
            AccountKind.CASH -> AccountProduct.ESPECES
            AccountKind.SAVINGS -> AccountProduct.LIVRET_BANCAIRE
        }
}

// --- Catégories et budget -----------------------------------------------------

/** Les trois familles du 50/30/20 adapté, plus les revenus et les mouvements internes. */
@Serializable
enum class Family { ESSENTIEL, PLAISIR, AVENIR, REVENU, INTERNE }

@Serializable
data class Category(
    val id: String,
    val name: String,
    val family: Family,
    val icon: String = "",
    /** Jamais proposée à la coupe par les conseils (profil de vie). */
    val nonNegotiable: Boolean = false,
    val archived: Boolean = false,
)

/** Enveloppe mensuelle stricte d'une catégorie de dépense. */
@Serializable
data class Envelope(val categoryId: String, val monthlyCents: Long)

/** Transfert entre enveloppes pour couvrir un dépassement, dans une période donnée. */
@Serializable
data class EnvelopeMove(
    val id: String,
    /** Début (AAAA-MM-JJ) de la période budgétaire concernée. */
    val periodStart: String,
    val fromCategoryId: String,
    val toCategoryId: String,
    val cents: Long,
)

// --- Opérations ---------------------------------------------------------------

/** D'où vient une opération : la provenance compte pour le rapprochement. */
@Serializable
enum class TransactionSource { MANUAL, NOTIFICATION, IMPORT, RECEIPT }

@Serializable
enum class TransactionStatus { TO_VALIDATE, VALIDATED, IGNORED }

@Serializable
data class Transaction(
    val id: String,
    /** Instant ISO-8601 de création de l'enregistrement. */
    val createdAt: String,
    /** Date comptable, AAAA-MM-JJ. */
    val date: String,
    /** Montant signé en centimes : négatif pour une dépense, positif pour un revenu. */
    val amountCents: Long,
    val label: String,
    val accountId: String? = null,
    val categoryId: String? = null,
    val source: TransactionSource = TransactionSource.MANUAL,
    val status: TransactionStatus = TransactionStatus.TO_VALIDATE,
    /** Pourquoi cette catégorie a été proposée (règle apprise, mot-clé…). */
    val suggestionReason: String? = null,
    /** Texte d'origine (notification, ligne de relevé), pour vérifier ou corriger. */
    val sourceText: String? = null,
    /** Identifiant de la ligne de relevé importée qui confirme l'opération. */
    val importRef: String? = null,
    val note: String = "",
)

// --- Classement appris --------------------------------------------------------

@Serializable
enum class RuleOrigin { LEARNED, USER }

/** « Les opérations dont le marchand normalisé vaut [merchantKey] vont dans [categoryId]. » */
@Serializable
data class CategoryRule(
    val id: String,
    val merchantKey: String,
    val categoryId: String,
    val hits: Int = 1,
    val origin: RuleOrigin = RuleOrigin.LEARNED,
)

// --- Prélèvements et charges fixes -------------------------------------------

@Serializable
enum class Frequency { MONTHLY, YEARLY }

@Serializable
data class FixedCharge(
    val id: String,
    val name: String,
    /** Montant d'une échéance, en centimes positifs. */
    val amountCents: Long,
    /** Jour d'échéance dans le mois (1 à 31). */
    val dayOfMonth: Int,
    val categoryId: String? = null,
    val accountId: String? = null,
    /** Indispensable (assurance, téléphone…) ou non (abonnement loisir). */
    val essential: Boolean = true,
    val frequency: Frequency = Frequency.MONTHLY,
    /** Mois de l'échéance annuelle (1 à 12). */
    val month: Int? = null,
    val active: Boolean = true,
) {
    /** Coût ramené au mois. */
    val monthlyCents: Long get() = if (frequency == Frequency.YEARLY) amountCents / 12 else amountCents
}

// --- Objectifs ----------------------------------------------------------------

@Serializable
enum class Pace { TRANQUILLE, EQUILIBRE, RAPIDE }

@Serializable
data class Goal(
    val id: String,
    val name: String,
    val targetCents: Long,
    val savedCents: Long = 0,
    /** Échéance souhaitée (AAAA-MM-JJ), facultative. */
    val deadline: String? = null,
    val pace: Pace = Pace.EQUILIBRE,
    val createdAt: String,
    val done: Boolean = false,
)

// --- Profil de vie et réglages ------------------------------------------------

@Serializable
data class LifeProfile(
    /** Paie nette mensuelle habituelle (centimes ; 0 = déduite de l'historique). */
    val netPayCents: Long = 0,
    /** Jour habituel de la paie (31 = fin de mois). */
    val usualPayday: Int = 28,
    val hasRent: Boolean = false,
    /** Contraintes et habitudes expliquées librement. */
    val notes: String = "",
    /** Épargne de précaution visée, en mois de dépenses essentielles. */
    val emergencyMonths: Int = 1,
    /** Montant laissé sur une épargne non protégée quand on la mobilise. */
    val savingsCushionCents: Long = 10_000,
    val onboardingDone: Boolean = false,
    /** Étape du premier lancement en cours (reprise après verrouillage). */
    val onboardingStep: Int = 0,
)

@Serializable
data class AppSettings(
    /** Délai de grâce avant verrouillage après passage en arrière-plan (0, 30 ou 60 s). */
    val lockGraceSeconds: Int = 0,
    val eveningReminder: Boolean = true,
    val eveningHour: Int = 20,
    val weeklyReview: Boolean = true,
    val backupReminder: Boolean = true,
    /** Alerte si aucune capture depuis ce nombre de jours (0 = désactivée). */
    val captureGapDays: Int = 4,
    /** App source d'une capture → compte à débiter (réglage explicite). */
    val captureAccounts: Map<String, String> = emptyMap(),
    /** Visites guidées déjà vues (par écran). */
    val toursSeen: Set<String> = emptySet(),
)
