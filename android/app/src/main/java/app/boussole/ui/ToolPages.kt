package app.boussole.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import app.boussole.capture.CaptureService
import app.boussole.core.capture.ReceiptParser
import app.boussole.core.domain.CsvExport
import app.boussole.core.domain.Family
import app.boussole.core.domain.Review
import app.boussole.core.domain.Transaction
import app.boussole.core.domain.TransactionSource
import app.boussole.core.domain.VaultData
import app.boussole.core.importer.ImportError
import app.boussole.core.importer.Reconcile
import app.boussole.core.importer.StatementImport
import app.boussole.core.importer.StatementLine
import app.boussole.receipt.ReceiptScanner
import app.boussole.reminders.Reminders
import app.boussole.security.DeviceIntegrity
import app.boussole.security.HardwareKey
import app.boussole.vault.VaultState
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

private val KNOWN_SOURCES = mapOf(
    "com.boursorama.android.clients" to "Boursobank",
    "com.samsung.android.spay" to "Samsung Wallet",
    "fr.caisseepargne.android.mobilebanking" to "Caisse d'Épargne",
    "app.boussole.e2e" to "Tests automatiques",
)

private fun dateTime(millis: Long): String =
    if (millis == 0L) "jamais" else LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
        .let { "${shortDate(it.toLocalDate())} à ${"%02d:%02d".format(it.hour, it.minute)}" }

// --- Capture --------------------------------------------------------------------

@Composable
fun CapturePage(ctx: Ctx, data: VaultData) {
    val context = ctx.activity
    val store = ctx.app.captureStore
    val config by store.config.collectAsState()
    var check by remember { mutableIntStateOf(0) }
    val listening = remember(check) { NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName) }
    var pickAccountFor by remember { mutableStateOf<String?>(null) }
    var diagnostics by remember { mutableStateOf(ctx.vault.diagnostics()) }

    Page {
        InfoCard(
            if (listening) "Lecture des notifications : active" else "Lecture des notifications : inactive",
            if (listening) "Les notifications des apps ci-dessous deviennent des dépenses à valider. Rien d'autre n'est lu."
            else "Autorise Boussole à lire les notifications. Comme l'app n'est pas installée depuis le Play Store, Android " +
                "demande d'abord : Infos de l'app → ⋮ → « Autoriser les paramètres restreints ».",
            if (listening) Tone.NEUTRAL else Tone.WARNING,
        )
        if (!listening) {
            OutlinedButton(onClick = {
                ctx.app.beginExternalAction()
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            }, modifier = Modifier.fillMaxWidth()) { Text("1. Infos de l'app (paramètres restreints)") }
            OutlinedButton(onClick = {
                ctx.app.beginExternalAction()
                val component = ComponentName(context, CaptureService::class.java).flattenToString()
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component)
                runCatching { context.startActivity(intent) }
                    .onFailure { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            }, modifier = Modifier.fillMaxWidth()) { Text("2. Autoriser l'accès aux notifications") }
        }
        TextButton(onClick = { check++ }) { Text("Vérifier à nouveau") }
        Muted("Dernière capture : ${dateTime(config.lastCaptureAt)}")

        SectionTitle("Apps lues")
        config.sources.sorted().forEach { pkg ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(KNOWN_SOURCES[pkg] ?: config.seen[pkg]?.label ?: pkg)
                    Muted("Compte : " + accountLabel(data.account(data.settings.captureAccounts[pkg])).replace("Sans compte", "deviné automatiquement"))
                }
                TextButton(onClick = { pickAccountFor = pkg }) { Text("Compte") }
                TextButton(onClick = { store.update { it.copy(sources = it.sources - pkg) } }) { Text("Retirer") }
            }
            HorizontalDivider()
        }

        SectionTitle("Diagnostic")
        LabeledSwitch(
            "Mode diagnostic", config.diagnostic, { on -> store.update { it.copy(diagnostic = on) } },
            "Retient le nom des apps qui envoient des notifications, et les notifications des apps lues que l'analyse n'a pas comprises (chiffrées).",
            tag = "capture_diagnostic",
        )
        if (config.diagnostic) {
            val others = config.seen.filterKeys { it !in config.sources }
            if (others.isNotEmpty()) {
                Muted("Apps vues récemment (touche pour les ajouter si c'est une banque) :")
                others.entries.sortedByDescending { it.value.lastSeen }.forEach { (pkg, seen) ->
                    ClickableRow({ store.update { it.copy(sources = it.sources + pkg) } }) {
                        Column(Modifier.weight(1f)) {
                            Text(seen.label)
                            Muted("$pkg · ${seen.count} notification(s)")
                        }
                        Text("+ Ajouter")
                    }
                }
            }
            Muted("Notifications non comprises : ${diagnostics.size}")
            diagnostics.take(20).forEach { item ->
                Column {
                    Text("${KNOWN_SOURCES[item.packageName] ?: item.packageName} · ${dateTime(item.postedAtMillis)}")
                    Muted("${item.title} — ${item.text}")
                    TextButton(onClick = { copySensitive(ctx, "${item.packageName}\n${item.title}\n${item.text}") }) { Text("Copier") }
                }
                HorizontalDivider()
            }
            if (diagnostics.isNotEmpty()) {
                TextButton(onClick = {
                    store.clearDiagnostics()
                    diagnostics = emptyList()
                }) { Text("Effacer le diagnostic") }
            }
        }
    }
    pickAccountFor?.let { pkg ->
        AccountPicker(data, onPick = { accountId ->
            ctx.update { d -> d.copy(settings = d.settings.copy(captureAccounts = d.settings.captureAccounts + (pkg to accountId))) }
            pickAccountFor = null
        }, onDismiss = { pickAccountFor = null })
    }
}

/** Copie marquée sensible, presse-papiers vidé au bout d'une minute. */
fun copySensitive(ctx: Ctx, text: String) {
    val clipboard = ctx.activity.getSystemService(ClipboardManager::class.java)
    val clip = ClipData.newPlainText("Boussole", text)
    clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    clipboard.setPrimaryClip(clip)
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runCatching { clipboard.clearPrimaryClip() } }, 60_000)
}

// --- Rappels --------------------------------------------------------------------

@Composable
fun RemindersPage(ctx: Ctx, data: VaultData) {
    val s = data.settings
    var granted by remember { mutableStateOf(Reminders.canNotify(ctx.activity)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    fun save(transform: (app.boussole.core.domain.AppSettings) -> app.boussole.core.domain.AppSettings) {
        ctx.scope.launch {
            ctx.vault.update { d -> d.copy(settings = transform(d.settings)) }
            Reminders.schedule(ctx.activity)
        }
    }
    Page {
        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InfoCard("Notifications désactivées", "Autorise les notifications pour recevoir les rappels.", Tone.WARNING)
            PrimaryButton("Autoriser les notifications", { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }, tag = "reminders_permission")
        }
        Muted("Les rappels sont créés sur le téléphone et n'affichent jamais de montant sur l'écran verrouillé.")
        LabeledSwitch("Le soir : dépenses à valider", s.eveningReminder, { on -> save { it.copy(eveningReminder = on) } })
        Text("Heure du rappel")
        Choices(listOf(18, 19, 20, 21, 22).map { it to "${it} h" }, s.eveningHour, { h -> save { it.copy(eveningHour = h) } })
        LabeledSwitch("Le dimanche : bilan de la semaine", s.weeklyReview, { on -> save { it.copy(weeklyReview = on) } })
        LabeledSwitch("Le dimanche : rappel de sauvegarde", s.backupReminder, { on -> save { it.copy(backupReminder = on) } }, "Si la dernière date de plus de 7 jours.")
        Text("Alerte si aucune dépense captée depuis")
        Choices(listOf(0 to "Jamais", 2 to "2 jours", 4 to "4 jours", 7 to "7 jours"), s.captureGapDays, { d -> save { it.copy(captureGapDays = d) } })
        Text("Délai avant verrouillage en arrière-plan")
        Choices(listOf(0 to "Immédiat", 30 to "30 s", 60 to "1 min"), s.lockGraceSeconds, { g -> save { it.copy(lockGraceSeconds = g) } }, tagPrefix = "lock_grace")
    }
}

// --- Import de relevé -------------------------------------------------------------

@Composable
fun ImportPage(ctx: Ctx, data: VaultData) {
    val checking = data.accounts
    var accountId by remember { mutableStateOf(checking.firstOrNull()?.id) }
    var lines by remember { mutableStateOf<List<StatementLine>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val bytes = ctx.activity.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw ImportError("Fichier illisible.")
            lines = StatementImport.parse(bytes)
            message = null
        } catch (e: ImportError) {
            lines = null
            message = e.message
        } catch (e: Exception) {
            lines = null
            message = "Lecture impossible : ${e.message ?: e.javaClass.simpleName}"
        }
    }
    Page {
        Muted(
            "Sur le site de ta banque, exporte les opérations du compte en CSV ou OFX, puis choisis le fichier. " +
                "Ce qui a déjà été capté par notification est rapproché, rien n'est créé en double.",
        )
        Text("Compte concerné")
        Choices(checking.map { it.id to accountLabel(it) }, accountId, { accountId = it })
        PrimaryButton("Choisir le fichier", {
            ctx.app.beginExternalAction()
            picker.launch(arrayOf("*/*"))
        }, tag = "import_pick")
        message?.let { InfoCard("Import", it, Tone.WARNING) }
        lines?.let { found ->
            InfoCard(
                "${found.size} opération(s) trouvée(s)",
                "Du ${shortDate(found.minOf { it.date })} au ${shortDate(found.maxOf { it.date })}.",
            )
            PrimaryButton("Importer dans « ${accountLabel(data.account(accountId))} »", {
                val account = accountId ?: return@PrimaryButton
                ctx.scope.launch {
                    var summary = ""
                    ctx.vault.update { d ->
                        val result = Reconcile.apply(d, account, found, Instant.now())
                        summary = "${result.added} ajoutée(s), ${result.matched} rapprochée(s), ${result.alreadyImported} déjà importée(s)."
                        result.data
                    }
                    lines = null
                    message = summary
                }
            }, enabled = accountId != null, tag = "import_apply")
        }
        OutlinedButton(onClick = { ctx.nav.replace(Route.Validation) }, modifier = Modifier.fillMaxWidth()) { Text("Aller à la validation") }
    }
}

// --- Ticket de caisse --------------------------------------------------------------

@Composable
fun ReceiptPage(ctx: Ctx) {
    val context = ctx.activity
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var photoUri by remember { mutableStateOf<Uri?>(null) }

    fun analyze(uri: Uri) {
        busy = true
        error = null
        ctx.scope.launch {
            try {
                val text = ReceiptScanner.recognize(context, uri)
                val receipt = ReceiptParser.parse(text)
                val cents = receipt.amountCents
                ReceiptScanner.deletePhoto(context)
                if (cents == null) {
                    error = "Montant introuvable sur le ticket. Essaie une photo plus nette, à plat, bien éclairée."
                } else {
                    val draft = Transaction(
                        id = UUID.randomUUID().toString(),
                        createdAt = Instant.now().toString(),
                        date = (receipt.date ?: ctx.today).toString(),
                        amountCents = -cents,
                        label = receipt.merchant ?: "Ticket",
                        categoryId = receipt.categoryId,
                        source = TransactionSource.RECEIPT,
                        sourceText = "Ticket : " + text.lines().take(8).joinToString(" / "),
                    )
                    ctx.nav.replace(Route.Edit(null, draft))
                }
            } catch (e: Exception) {
                error = "Analyse impossible : ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = photoUri
        if (ok && uri != null) analyze(uri)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) analyze(uri)
    }
    Page {
        Muted("Le texte du ticket est lu sur le téléphone, la photo est effacée juste après. Tu vérifies tout avant d'enregistrer.")
        PrimaryButton("Prendre le ticket en photo", {
            ctx.app.beginExternalAction()
            val uri = ReceiptScanner.photoUri(context)
            photoUri = uri
            camera.launch(uri)
        }, busy = busy)
        SecondaryButton("Choisir une image", {
            ctx.app.beginExternalAction()
            gallery.launch("image/*")
        })
        error?.let { InfoCard("Ticket", it, Tone.WARNING) }
    }
}

// --- Sauvegarde et export ------------------------------------------------------------

@Composable
fun BackupPage(ctx: Ctx, data: VaultData) {
    val config by ctx.app.captureStore.config.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmCsv by remember { mutableStateOf(false) }

    fun share(file: File, type: String, title: String) {
        val uri = FileProvider.getUriForFile(ctx.activity, "${ctx.activity.packageName}.backups", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            this.type = type
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.app.beginExternalAction()
        ctx.activity.startActivity(Intent.createChooser(send, title))
    }

    Page {
        InfoCard(
            "Sauvegarde chiffrée",
            "Le fichier est chiffré avant de quitter l'app ; seule ta phrase de récupération l'ouvre. " +
                "Envoie-le vers Proton Drive. Dernière sauvegarde : ${dateTime(config.lastBackupAt)}.",
        )
        error?.let { ErrorText(it) }
        PrimaryButton("Exporter une sauvegarde chiffrée", {
            busy = true
            error = null
            ctx.scope.launch {
                try {
                    share(ctx.vault.exportBackup(), "application/octet-stream", "Envoyer la sauvegarde chiffrée")
                } catch (e: Exception) {
                    error = "Échec de l'export : ${e.message ?: e.javaClass.simpleName}"
                } finally {
                    busy = false
                }
            }
        }, busy = busy, tag = "backup_export")
        Muted("Pour restaurer sur un nouveau téléphone : installe l'app, puis « Restaurer une sauvegarde » au premier lancement.")
        SectionTitle("Export lisible (CSV)")
        Muted("Pour un tableur. Ce fichier n'est PAS chiffré : ne le garde pas plus que nécessaire.")
        OutlinedButton(onClick = { confirmCsv = true }, modifier = Modifier.fillMaxWidth()) { Text("Exporter les opérations en CSV") }
    }
    if (confirmCsv) {
        Confirm(
            "Export non chiffré",
            "Toutes tes opérations seront lisibles par quiconque obtient ce fichier. Continuer ?",
            "Exporter",
            onConfirm = {
                confirmCsv = false
                ctx.scope.launch {
                    share(ctx.vault.exportText("boussole-operations-${ctx.today}.csv", CsvExport.transactions(data)), "text/csv", "Exporter les opérations")
                }
            },
            onDismiss = { confirmCsv = false },
        )
    }
}

// --- Sécurité ---------------------------------------------------------------------

@Composable
fun SecurityPage(ctx: Ctx, state: VaultState.Unlocked) {
    var changing by remember { mutableIntStateOf(0) }
    var newPassword by remember { mutableStateOf("") }
    val integrity = remember { DeviceIntegrity.check(ctx.activity) }
    when (changing) {
        1 -> ChooseSecretStep("Nouveau mot de passe", "Il remplacera l'actuel.", onValid = { newPassword = it; changing = 2 }, onBack = { changing = 0 })
        2 -> BiometricStep(
            ctx.activity,
            title = "Ton empreinte",
            explanation = "Une nouvelle clé matérielle scelle le coffre avec ton nouveau mot de passe.",
            buttonLabel = "Changer le mot de passe",
            prepare = ctx.vault::prepareNewHardwareKey,
            action = { cipher ->
                ctx.vault.changePassword(newPassword, cipher)
                changing = 0
            },
            onBack = { changing = 1 },
        )
        else -> Page {
            InfoCard(
                "Protection de la clé",
                when (state.keyLevel) {
                    HardwareKey.Level.STRONGBOX -> "Puce sécurisée StrongBox : la clé ne peut pas sortir du téléphone."
                    HardwareKey.Level.TEE -> "Environnement sécurisé (TEE) du téléphone."
                    HardwareKey.Level.SOFTWARE -> "Clé logicielle uniquement : aucune puce sécurisée détectée."
                    HardwareKey.Level.UNKNOWN -> "Niveau de protection inconnu."
                },
            )
            InfoCard(
                "État du téléphone",
                if (integrity.isEmpty()) "Aucun signe de modification (root) détecté." else integrity.joinToString("\n"),
                if (integrity.isEmpty()) Tone.NEUTRAL else Tone.DANGER,
            )
            InfoCard(
                "Ce qui protège tes données",
                "Pas d'accès Internet · chiffrement AES-256 · empreinte ET mot de passe · captures d'écran bloquées · " +
                    "aucune sauvegarde vers Google ou Samsung.",
            )
            PrimaryButton("Changer le mot de passe", { changing = 1 }, tag = "security_change_password")
            Muted("Le délai avant verrouillage se règle dans Rappels.")
        }
    }
}

// --- Bilan de la semaine ------------------------------------------------------------------

@Composable
fun ReviewPage(ctx: Ctx, data: VaultData) {
    val review = remember(data) { Review.week(data, ctx.today) }
    Page {
        Muted("Les 7 derniers jours comparés aux 7 précédents.")
        listOf(Family.ESSENTIEL, Family.PLAISIR, Family.AVENIR).forEach { family ->
            val now = review.spentByFamily[family] ?: 0
            val before = review.previousSpentByFamily[family] ?: 0
            Row(Modifier.fillMaxWidth()) {
                Text("${familyIcon(family)} ${familyLabel(family)}", Modifier.weight(1f))
                Text("${euros(now)} (avant : ${euros(before)})")
            }
        }
        if (review.topCategories.isNotEmpty()) {
            SectionTitle("Postes principaux")
            review.topCategories.forEach { (category, cents) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(categoryLabel(category), Modifier.weight(1f))
                    Text(euros(cents))
                }
            }
        }
        Text("${review.validatedCount} opération(s) validée(s) cette semaine.")
        if (review.pendingCount > 0) {
            PrimaryButton("Valider les ${review.pendingCount} en attente", { ctx.nav.replace(Route.Validation) })
        }
        OutlinedButton(onClick = { ctx.nav.back(); ctx.nav.tab = Tab.BUDGET }, modifier = Modifier.fillMaxWidth().testTag("review_budget")) {
            Text("Voir mes enveloppes")
        }
    }
}
