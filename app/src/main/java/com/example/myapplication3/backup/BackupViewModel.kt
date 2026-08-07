package com.example.myapplication3.backup

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** One plain-English line for the Settings screen to show after save/restore. */
data class BackupMessage(
    val text: String,
    /** True = something went wrong (show in the caution style, with a next step). */
    val isError: Boolean
)

/**
 * P0 #10 — Settings-screen ViewModel for "Save my data" / "Bring my data back".
 *
 * The screen supplies a SAF document [Uri] (ACTION_CREATE_DOCUMENT for export,
 * ACTION_OPEN_DOCUMENT for import) plus the [ContentResolver]; this class does
 * the streaming off the main thread and reports back in simple English (U9.5:
 * no error codes, never stuck without a next step).
 *
 * Wiring for the Settings screen (owned by another pass):
 *   val vm: BackupViewModel = hiltViewModel()
 *   val saveLauncher = rememberLauncherForActivityResult(
 *       ActivityResultContracts.CreateDocument("application/json")
 *   ) { uri -> uri?.let { vm.export(it, context.contentResolver) } }
 *   // launch with: saveLauncher.launch(vm.suggestedFileName())
 *   val openLauncher = rememberLauncherForActivityResult(
 *       ActivityResultContracts.OpenDocument()
 *   ) { uri -> uri?.let { vm.import(it, context.contentResolver) } }
 *   // launch with: openLauncher.launch(arrayOf("application/json", "text/plain"))
 *   // (add the any-type MIME star-slash-star too — spelled out here because the
 *   //  literal would end this comment block)
 *   // show vm.message in a snackbar/dialog; call vm.clearMessage() after showing.
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    private val dataBackup: DataBackup
) : ViewModel() {

    /** The one-line result to show the user; null = nothing to show right now. */
    private val _message = MutableStateFlow<BackupMessage?>(null)
    val message: StateFlow<BackupMessage?> = _message.asStateFlow()

    /** True while a save/restore is running — disable both rows meanwhile. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** File name to pass to ACTION_CREATE_DOCUMENT, e.g. "my-trade-diary-2026-07-18.json". */
    fun suggestedFileName(): String =
        "my-trade-diary-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".json"

    /** Write the whole diary (trades + settings + SIP) to the user's chosen file. */
    fun export(uri: Uri, resolver: ContentResolver) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val json = dataBackup.exportJson()
                    // "wt" truncates an existing file — re-saving over an old
                    // backup must never leave stale bytes at the tail.
                    val out = resolver.openOutputStream(uri, "wt")
                        ?: error("stream unavailable")
                    out.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                }
            }
            _message.value = result.fold(
                onSuccess = {
                    BackupMessage(
                        "Saved. Keep this file safe — it is your trade diary.",
                        isError = false
                    )
                },
                onFailure = {
                    BackupMessage(
                        "Could not save the file. Try again and pick a different folder.",
                        isError = true
                    )
                }
            )
            _busy.value = false
        }
    }

    /** Read a backup file and bring the diary back. Safe to run on a full app too. */
    fun import(uri: Uri, resolver: ContentResolver) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val text = resolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText() }
                        ?: error("stream unavailable")
                    dataBackup.importJson(text)
                }
            }
            _message.value = outcome.fold(
                onSuccess = { r -> messageFor(r) },
                onFailure = {
                    BackupMessage(
                        "Could not read that file. Pick the file you saved earlier.",
                        isError = true
                    )
                }
            )
            _busy.value = false
        }
    }

    /** Call after the message has been shown, so it does not pop up again. */
    fun clearMessage() {
        _message.value = null
    }

    /** Turn the counts into one honest, simple-English line. */
    private fun messageFor(r: ImportResult): BackupMessage {
        // Nothing usable at all → say so plainly with the next step.
        if (r.tradesAdded == 0 && r.tradesSkipped == 0 &&
            r.prefsRestored == 0 && !r.sipRestored && r.errors.isNotEmpty()
        ) {
            return BackupMessage(r.errors.first(), isError = true)
        }
        val parts = mutableListOf<String>()
        parts += when {
            r.tradesAdded > 0 && r.tradesSkipped > 0 ->
                "Restored ${r.tradesAdded} trades (${r.tradesSkipped} were already here)."
            r.tradesAdded > 0 -> "Restored ${r.tradesAdded} trades."
            r.tradesSkipped > 0 -> "All ${r.tradesSkipped} trades were already here."
            else -> "No trades were in the file."
        }
        if (r.prefsRestored > 0) parts += "Your settings are back."
        if (r.sipRestored) parts += "Your SIP record is back."
        if (r.errors.isNotEmpty()) {
            parts += "A small part of the file could not be read — everything else was restored."
        }
        return BackupMessage(parts.joinToString(" "), isError = false)
    }
}
