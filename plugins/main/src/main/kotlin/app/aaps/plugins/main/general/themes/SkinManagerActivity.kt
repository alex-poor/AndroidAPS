package app.aaps.plugins.main.general.themes

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import app.aaps.core.compose.theme.AapsSkinState
import app.aaps.core.compose.theme.AapsSkins
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.SkinFormatException
import app.aaps.core.compose.theme.SkinSpec
import app.aaps.core.compose.theme.toSkinHex
import app.aaps.core.interfaces.logging.AAPSLogger
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

    private var state by mutableStateOf(SkinManagerState())

    /** Set just before the export picker opens, so its callback knows which skin was meant. */
    private var pendingExportId: String? = null

    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        try {
            val skin = contentResolver.openInputStream(uri)?.use { skinStore.install(it) }
                ?: throw SkinFormatException("Could not open that file.")
            themeSwitcherPlugin.reloadInstalledSkins()
            refresh()
            ToastUtils.okToast(this, "Installed ${skin.label}")
        } catch (e: SkinFormatException) {
            // Expected: the file was wrong. Show the validator's own words rather than a crash.
            state = state.copy(error = e.message)
        } catch (e: Exception) {
            aapsLogger.error(LTag.UI, "Skin import failed", e)
            state = state.copy(error = e.message ?: "Could not read that file.")
        }
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
        setContentView(
            ComposeView(this).apply {
                setContent {
                    AapsTheme {
                        SkinManagerScreen(
                            state = state,
                            actions = SkinManagerActions(
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

    override fun onSupportNavigateUp(): Boolean = also { finish() }.let { true }

    private fun refresh() {
        state = SkinManagerState(
            installed = AapsSkins.installed.map { skin ->
                val spec = skinStore.specOf(skin.id)
                SkinManagerState.entryOf(skin, spec?.author?.let { "by $it" } ?: spec?.description, removable = true)
            },
            builtIn = AapsSkins.builtIn.map { SkinManagerState.entryOf(it, null, removable = false) },
            activeId = AapsSkinState.skin.id,
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
