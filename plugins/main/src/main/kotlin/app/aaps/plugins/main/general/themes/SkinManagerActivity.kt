package app.aaps.plugins.main.general.themes

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import app.aaps.core.compose.theme.AapsAppearances
import app.aaps.core.compose.theme.AapsSkinState
import app.aaps.core.compose.theme.AapsSkins
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.SkinFormatException
import app.aaps.core.compose.theme.AapsUiMode
import app.aaps.core.compose.theme.SkinSpec
import app.aaps.core.compose.theme.toSkinHex
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.plugins.main.general.themes.compose.SkinManagerActions
import app.aaps.plugins.main.general.themes.compose.SkinManagerScreen
import app.aaps.plugins.main.general.themes.compose.SkinManagerState
import javax.inject.Inject

/**
 * Manage installed skin files.
 *
 * All file access goes through the storage access framework, so this needs no storage permission and
 * the user picks exactly which file is read or written. Importing copies the bundle into app-private
 * storage; nothing is left pointing at a document the app does not control.
 */
class SkinManagerActivity : TranslatedDaggerAppCompatActivity() {

    @Inject lateinit var skinStore: SkinStore
    @Inject lateinit var themeSwitcherPlugin: ThemeSwitcherPlugin
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var preferences: Preferences

    private var state by mutableStateOf(SkinManagerState())

    /** Set just before the export picker opens, so its callback knows which skin was meant. */
    private var pendingExportId: String? = null

    // No confirmation here: the user just picked this file themselves, which is the agreement.
    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { installFrom(it) }
    }

    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument(SkinStore.MIME_TYPE)) { uri ->
        val id = pendingExportId
        pendingExportId = null
        uri ?: return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                if (id == null) skinStore.exportTemplate(templateSpec(), out) else skinStore.export(id, out)
            }
            ToastUtils.okToast(this, "Exported")
        } catch (e: Exception) {
            aapsLogger.error(LTag.UI, "Skin export failed", e)
            ToastUtils.errorToast(this, e.message ?: "Could not write that file.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Skins"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        refresh()
        handleIncomingFile(intent)
        setContentView(
            ComposeView(this).apply {
                setContent {
                    AapsTheme {
                        SkinManagerScreen(
                            state = state,
                            actions = SkinManagerActions(
                                onSelect = ::select,
                                onImport = { importFile.launch(arrayOf("*/*")) },
                                onExportTemplate = {
                                    pendingExportId = null
                                    exportFile.launch("my-skin.${SkinStore.FILE_EXTENSION}")
                                },
                                onExport = { id ->
                                    pendingExportId = id
                                    exportFile.launch("$id.${SkinStore.FILE_EXTENSION}")
                                },
                                onRemove = ::confirmRemove,
                                onDismissError = { state = state.copy(error = null) }
                            )
                        )
                    }
                }
            }
        )
    }

    /** A skin tapped in another app — a mail attachment, a file manager — arrives here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingFile(intent)
    }

    /**
     * Install a bundle handed over by another app, but only after saying whose it is and asking.
     *
     * This activity is exported so a `.aapsskin` can be opened straight from wherever it arrived,
     * which means any app on the phone can point it at a URI. Installing silently on that basis would
     * let something else change how this app looks without the user ever agreeing to it — so the
     * manifest is read first, purely to name the skin in a confirmation, and nothing touches storage
     * until the user says yes.
     */
    private fun handleIncomingFile(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        intent.data = null   // so a configuration change does not re-ask

        val spec = try {
            contentResolver.openInputStream(uri)?.use { skinStore.peek(it) }
                ?: throw SkinFormatException("Could not open that file.")
        } catch (e: SkinFormatException) {
            state = state.copy(error = e.message)
            return
        } catch (e: Exception) {
            aapsLogger.error(LTag.UI, "Could not read incoming skin", e)
            state = state.copy(error = e.message ?: "Could not read that file.")
            return
        }

        val replacing = AapsSkins.installed.any { it.id == spec.id }
        val by = spec.author?.let { "by $it" }
        val note = listOfNotNull(by, spec.description).joinToString("\n").ifBlank { null }
        OKDialog.showConfirmation(
            this,
            if (replacing) "Replace ${spec.label}?" else "Install ${spec.label}?",
            note ?: "Add this skin to the theme list.",
            { installFrom(uri) }
        )
    }

    private fun installFrom(uri: android.net.Uri) {
        try {
            val skin = contentResolver.openInputStream(uri)?.use { skinStore.install(it) }
                ?: throw SkinFormatException("Could not open that file.")
            themeSwitcherPlugin.reloadInstalledSkins()
            refresh()
            ToastUtils.okToast(this, "Installed ${skin.label}")
        } catch (e: SkinFormatException) {
            state = state.copy(error = e.message)
        } catch (e: Exception) {
            aapsLogger.error(LTag.UI, "Skin install failed", e)
            state = state.copy(error = e.message ?: "Could not read that file.")
        }
    }

    override fun onSupportNavigateUp(): Boolean = also { finish() }.let { true }

    /**
     * Apply an appearance immediately.
     *
     * Written straight to the preference then applied, rather than waiting for an EventPreferenceChange
     * that a direct write does not raise. The Compose surfaces repaint from snapshot state, so the
     * change is visible behind this screen before the tap finishes.
     */
    private fun select(id: String) {
        preferences.put(StringKey.GeneralSkin, id)
        themeSwitcherPlugin.applyAppearance()
        refresh()
    }

    private fun refresh() {
        // One list, in the order the picker used to offer: the built-in appearances first, then the
        // skins the user added.
        state = SkinManagerState(
            entries = AapsAppearances.all.map { appearance ->
                val installed = AapsSkins.installed.any { it.id == appearance.skin.id }
                val spec = if (installed) skinStore.specOf(appearance.skin.id) else null
                SkinManagerState.Entry(
                    id = appearance.id,
                    label = appearance.label,
                    byline = spec?.author?.let { "by $it" } ?: spec?.description,
                    // "Follow system" has no palette of its own to preview; everything else does.
                    swatches = if (appearance.mode == AapsUiMode.SYSTEM && !installed) null
                    else SkinManagerState.swatchesOf(appearance.skin.colors(appearance.mode != AapsUiMode.LIGHT)),
                    removable = installed
                )
            },
            activeId = AapsSkinState.appearanceId,
            error = state.error
        )
    }

    /**
     * Removing the skin in use would leave the app pointing at something that no longer exists, so
     * the confirmation says so and the appearance is re-applied afterwards — the id then fails to
     * resolve and falls back to the default rather than rendering nothing.
     */
    private fun confirmRemove(id: String) {
        val inUse = AapsSkinState.skin.id == id
        val label = AapsSkins.installed.firstOrNull { it.id == id }?.label ?: id
        OKDialog.showConfirmation(
            this,
            "Remove $label?",
            if (inUse) "This skin is in use. The app will go back to the default appearance." else "The skin file will be deleted from this phone.",
            {
                skinStore.uninstall(id)
                themeSwitcherPlugin.reloadInstalledSkins()
                if (inUse) themeSwitcherPlugin.applyAppearance()
                refresh()
            }
        )
    }

    /**
     * A working skin to edit, rather than a blank file and a token list.
     *
     * Seeded from the palette actually on screen, so what the author opens already matches what they
     * were looking at when they decided to change it.
     */
    private fun templateSpec(): SkinSpec {
        val c = AapsSkinState.skin.dark
        return SkinSpec(
            id = "my-skin",
            label = "My skin",
            author = null,
            description = "Edit skin.json, zip it back up, and import it.",
            cornerRadius = 18f,
            dark = SkinSpec.PaletteSpec(
                background = c.background.toSkinHex(),
                surface = c.surface.toSkinHex(),
                textPrimary = c.textPrimary.toSkinHex(),
                textSecondary = c.textSecondary.toSkinHex(),
                accent = c.accent.toSkinHex(),
                onAccent = c.onAccent.toSkinHex(),
                inRange = c.inRange.toSkinHex(),
                high = c.high.toSkinHex(),
                low = c.low.toSkinHex()
            )
        )
    }
}
