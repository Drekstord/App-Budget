package app.boussole.receipt

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reconnaissance du texte d'un ticket, entièrement sur le téléphone (modèle ML
 * Kit embarqué dans l'APK). L'app n'ayant pas accès à Internet, rien ne peut
 * être transmis. La photo est effacée dès l'analyse terminée.
 */
object ReceiptScanner {
    private fun photoFile(context: Context) = File(File(context.cacheDir, "receipts").apply { mkdirs() }, "ticket.jpg")

    /** Emplacement où l'appareil photo écrit la prise de vue. */
    fun photoUri(context: Context): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.backups", photoFile(context).apply { delete() })

    suspend fun recognize(context: Context, uri: Uri): String = suspendCancellableCoroutine { continuation ->
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = InputImage.fromFilePath(context, uri)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                recognizer.close()
                if (continuation.isActive) continuation.resume(result.text)
            }
            .addOnFailureListener { error ->
                recognizer.close()
                if (continuation.isActive) continuation.resumeWithException(error)
            }
    }

    fun deletePhoto(context: Context) {
        photoFile(context).delete()
    }
}
