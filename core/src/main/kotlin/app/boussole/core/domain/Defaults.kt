package app.boussole.core.domain

/**
 * Catégories de départ, pensées pour une vie sans loyer, et mots-clés de
 * classement initiaux. Les identifiants sont stables : ne jamais les renommer.
 */
object Defaults {
    const val SALARY = "cat.salaire"
    const val INTERNAL_TRANSFER = "cat.virement-interne"
    const val EMERGENCY_FUND = "cat.epargne-precaution"
    const val DEBT_REPAYMENT = "cat.remboursement"
    const val PROJECTS = "cat.projets"

    val categories: List<Category> = listOf(
        // Essentiel
        Category("cat.courses", "Courses", Family.ESSENTIEL, "🛒"),
        Category("cat.transport", "Transport & carburant", Family.ESSENTIEL, "🚗"),
        Category("cat.assurances", "Assurances", Family.ESSENTIEL, "🛡️"),
        Category("cat.telephone", "Téléphone & internet", Family.ESSENTIEL, "📱"),
        Category("cat.sante", "Santé", Family.ESSENTIEL, "💊"),
        Category("cat.foyer", "Participation au foyer", Family.ESSENTIEL, "🏠"),
        Category("cat.banque", "Frais bancaires", Family.ESSENTIEL, "🏦"),
        Category("cat.impots", "Impôts & taxes", Family.ESSENTIEL, "🧾"),
        // Plaisir
        Category("cat.restos", "Restaurants & cafés", Family.PLAISIR, "🍽️"),
        Category("cat.sorties", "Sorties & loisirs", Family.PLAISIR, "🎟️"),
        Category("cat.shopping", "Shopping & vêtements", Family.PLAISIR, "🛍️"),
        Category("cat.abonnements", "Abonnements loisirs", Family.PLAISIR, "📺"),
        Category("cat.musique", "Musique & hobbies", Family.PLAISIR, "🎸"),
        Category("cat.voyages", "Voyages", Family.PLAISIR, "✈️"),
        Category("cat.cadeaux", "Cadeaux", Family.PLAISIR, "🎁"),
        Category("cat.divers", "Divers", Family.PLAISIR, "📦"),
        // Avenir
        Category(EMERGENCY_FUND, "Épargne de précaution", Family.AVENIR, "🧱"),
        Category(DEBT_REPAYMENT, "Remboursement de découvert / dettes", Family.AVENIR, "⛓️"),
        Category(PROJECTS, "Projets & objectifs", Family.AVENIR, "🎯"),
        // Revenus
        Category(SALARY, "Salaire", Family.REVENU, "💼"),
        Category("cat.aides", "Aides", Family.REVENU, "🤝"),
        Category("cat.primes", "Primes", Family.REVENU, "⭐"),
        Category("cat.remboursements", "Remboursements reçus", Family.REVENU, "↩️"),
        Category("cat.autres-revenus", "Autres revenus", Family.REVENU, "➕"),
        // Interne
        Category(INTERNAL_TRANSFER, "Virement entre mes comptes", Family.INTERNE, "🔁"),
    )

    /**
     * Mots-clés (marchand normalisé) → catégorie, utilisés tant qu'aucune règle
     * apprise ne s'applique. Un mot-clé correspond à un mot entier du libellé.
     */
    val keywords: Map<String, String> = buildMap {
        fun add(category: String, vararg words: String) = words.forEach { put(it, category) }
        add(
            "cat.courses", "CARREFOUR", "LECLERC", "LIDL", "AUCHAN", "INTERMARCHE", "MONOPRIX", "FRANPRIX",
            "CASINO", "ALDI", "SUPER U", "HYPER U", "PICARD", "BIOCOOP", "GRAND FRAIS", "NETTO", "SPAR",
            "BOULANGERIE", "BOUCHERIE", "PRIMEUR", "MARCHE",
        )
        add(
            "cat.transport", "SNCF", "RATP", "TOTAL", "TOTALENERGIES", "ESSO", "SHELL", "BP", "AVIA",
            "AUTOROUTE", "PEAGE", "VINCI", "SANEF", "APRR", "PARKING", "INDIGO", "UBER", "BLABLACAR", "NAVIGO",
            "TCL", "TISSEO",
        )
        add("cat.assurances", "ASSURANCE", "MAIF", "MACIF", "MATMUT", "AXA", "ALLIANZ", "GROUPAMA", "MAAF")
        add("cat.telephone", "FREE", "ORANGE", "SFR", "BOUYGUES", "SOSH", "RED", "BOUYGUES TELECOM")
        add("cat.sante", "PHARMACIE", "PARAPHARMACIE", "LABORATOIRE", "DOCTOLIB", "MEDECIN", "DENTISTE", "OPTICIEN")
        add("cat.banque", "AGIOS", "COMMISSION", "FRAIS", "COTISATION")
        add("cat.impots", "DGFIP", "IMPOT", "TRESOR PUBLIC", "AMENDE", "ANTAI")
        add(
            "cat.restos", "MCDONALD", "MCDONALDS", "BURGER KING", "KFC", "SUBWAY", "DOMINOS", "PIZZA", "RESTAURANT",
            "BRASSERIE", "CAFE", "STARBUCKS", "UBER EATS", "DELIVEROO", "BOUILLON", "SUSHI", "KEBAB", "TACOS",
        )
        add(
            "cat.sorties", "CINEMA", "PATHE", "GAUMONT", "UGC", "MK2", "FNAC SPECTACLES", "TICKETMASTER", "BAR",
            "BOWLING", "MUSEE", "STEAM", "PLAYSTATION", "NINTENDO", "XBOX",
        )
        add(
            "cat.shopping", "AMAZON", "ZARA", "KIABI", "PRIMARK", "CELIO", "DECATHLON", "FNAC", "DARTY",
            "BOULANGER", "IKEA", "ACTION", "SHEIN", "VINTED", "ZALANDO", "UNIQLO", "SEPHORA", "NOCIBE",
        )
        add(
            "cat.abonnements", "NETFLIX", "SPOTIFY", "DEEZER", "DISNEY", "CANAL", "PRIME VIDEO", "AMAZON PRIME",
            "APPLE COM", "GOOGLE PLAY", "YOUTUBE", "CRUNCHYROLL", "PROTON", "ICLOUD", "PLAYSTATION PLUS",
        )
        add("cat.musique", "WOODBRASS", "THOMANN", "MUSIC", "MUSIQUE", "GUITARE")
        add("cat.voyages", "AIRBNB", "BOOKING", "RYANAIR", "EASYJET", "AIR FRANCE", "TRANSAVIA", "HOTEL", "OUIGO")
        add(SALARY, "SALAIRE", "PAIE", "REMUNERATION")
        add("cat.aides", "CAF", "PRIME ACTIVITE", "FRANCE TRAVAIL", "POLE EMPLOI", "APL")
        add("cat.remboursements", "CPAM", "AMELI", "MUTUELLE", "REMBOURSEMENT")
    }

    fun defaultAccounts(newId: () -> String): List<Account> = listOf(
        Account(newId(), "Compte courant", AccountKind.CHECKING, "Boursobank"),
        Account(newId(), "Compte courant", AccountKind.CHECKING, "Caisse d'Épargne"),
        Account(newId(), "Épargne", AccountKind.SAVINGS, "Caisse d'Épargne"),
    )
}
