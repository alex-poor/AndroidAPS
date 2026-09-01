package app.aaps.plugins.main.general.themes

import android.content.Context
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import app.aaps.core.compose.theme.AapsSkin
import app.aaps.core.compose.theme.SkinFormatException
import app.aaps.core.compose.theme.SkinSpec
import app.aaps.core.compose.theme.SkinValidation
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Installed skin files: unpacking them, listing them, handing them back as [AapsSkin], and packing
 * one up again to share.
 *
 * A `.aapsskin` bundle is a zip holding [SkinSpec.MANIFEST_NAME] and, optionally, a font. Zip rather
 * than a bare JSON so a skin with a font is still one file to send someone, and one file to receive.
 *
 * Skins are unpacked into app-private storage. That is not only convenient — it means reading one
 * back needs no storage permission and cannot be swapped underneath the app by another process
 * between validation and use.
 */
@Singleton
class SkinStore @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger
) {

    private val root: File get() = File(context.filesDir, "skins").also { it.mkdirs() }

    private fun dirFor(id: String) = File(root, id)

    /**
     * Installed skins, worst case an empty list.
     *
     * Never throws: a corrupt skin on disk must not stop the app from drawing, so a bad directory is
     * logged and skipped. The app always has its built-in skins to fall back on.
     */
    fun installed(): List<AapsSkin> =
        root.listFiles { f: File -> f.isDirectory }.orEmpty().sortedBy { it.name }.mapNotNull { dir ->
            runCatching { read(dir) }
                .onFailure { aapsLogger.error(LTag.UI, "Skipping unreadable skin '${dir.name}': ${it.message}") }
                .getOrNull()
        }

    /** The spec for an installed skin, for the sharing UI and for export. */
    fun specOf(id: String): SkinSpec? =
        runCatching { SkinSpec.parse(File(dirFor(id), SkinSpec.MANIFEST_NAME).readText()) }.getOrNull()

    private fun read(dir: File): AapsSkin {
        val spec = SkinSpec.parse(File(dir, SkinSpec.MANIFEST_NAME).readText())
        val skin = spec.toSkin(loadFont(dir, spec))
        // Re-validated on every read, not just at install: the rules can tighten in a later build,
        // and a skin installed under the old ones must not keep rendering an unreadable screen.
        val problems = SkinValidation.problems(skin)
        if (problems.isNotEmpty()) throw SkinFormatException("Skin '${spec.id}' is not legible: ${problems.first()}")
        return skin
    }

    /**
     * The bundled font, or null to keep the app's own.
     *
     * A font that will not load falls back rather than throwing. Losing a typeface makes the app look
     * wrong; refusing to render leaves the user unable to read their glucose, and only one of those
     * is worth failing over.
     */
    private fun loadFont(dir: File, spec: SkinSpec): FontFamily? {
        val name = spec.font?.file ?: return null
        val file = File(dir, name)
        if (!file.isFile) {
            aapsLogger.warn(LTag.UI, "Skin '${spec.id}' names font '$name' which is not in the bundle; using the app font")
            return null
        }
        return runCatching { FontFamily(Font(file)) }
            .onFailure { aapsLogger.warn(LTag.UI, "Skin '${spec.id}' font '$name' would not load (${it.message}); using the app font") }
            .getOrNull()
    }

    /**
     * Unpack and validate a bundle, replacing any skin already installed under the same id.
     *
     * Everything is staged in a temporary directory and only moved into place once the skin has been
     * parsed, its font loaded and its palette checked — so a bad file cannot half-replace a working
     * skin, and cannot leave the app pointing at something it will refuse to draw.
     *
     * @return the installed skin.
     * @throws SkinFormatException with a message meant for the person who wrote the file.
     */
    fun install(input: InputStream): AapsSkin {
        val staging = File(context.cacheDir, "skin-staging-${System.currentTimeMillis()}")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            unzip(input, staging)
            val manifest = File(staging, SkinSpec.MANIFEST_NAME)
            if (!manifest.isFile) throw SkinFormatException("Bundle has no ${SkinSpec.MANIFEST_NAME}.")

            val skin = read(staging)   // parses, loads the font, and enforces legibility

            val target = dirFor(skin.id)
            target.deleteRecursively()
            if (!staging.renameTo(target)) {
                staging.copyRecursively(target, overwrite = true)
                staging.deleteRecursively()
            }
            aapsLogger.info(LTag.UI, "Installed skin '${skin.id}'")
            return read(target)        // re-read so the font points at its final location
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Remove an installed skin. Built-ins are not on disk, so they are unaffected. */
    fun uninstall(id: String): Boolean = dirFor(id).deleteRecursively()

    /** Write an installed skin back out as a `.aapsskin` bundle. */
    fun export(id: String, out: OutputStream) {
        val dir = dirFor(id)
        if (!dir.isDirectory) throw SkinFormatException("Skin '$id' is not installed.")
        ZipOutputStream(out.buffered()).use { zip ->
            dir.listFiles().orEmpty().filter { it.isFile }.forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Write a skin file for one of the app's own palettes, as a starting point to edit.
     *
     * Handing someone a blank file and a token list is a poor invitation; handing them a working
     * skin they can open and change is a much better one.
     */
    fun exportTemplate(spec: SkinSpec, out: OutputStream) {
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(SkinSpec.MANIFEST_NAME))
            zip.write(SkinSpec.json.encodeToString(SkinSpec.serializer(), spec).toByteArray())
            zip.closeEntry()
        }
    }

    /**
     * Extract a bundle into [target].
     *
     * Deliberately strict, because this reads an archive that arrived from somewhere else:
     *  - entry names are reduced to a bare file name, so `../../databases/aaps.db` cannot escape
     *    the directory (the zip-slip traversal);
     *  - directories and nested paths are ignored — a skin is flat;
     *  - only the manifest, fonts, and licence/readme text are accepted, so a bundle cannot smuggle
     *    in anything else;
     *  - both per-entry and total size are capped, so a zip bomb cannot fill the phone.
     */
    private fun unzip(input: InputStream, target: File) {
        var total = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry: ZipEntry = zip.nextEntry ?: break
                val name = File(entry.name).name          // drops any directory component
                if (entry.isDirectory || name.isBlank() || name != entry.name) {
                    zip.closeEntry()
                    continue
                }
                val allowed = name == SkinSpec.MANIFEST_NAME ||
                    name.endsWith(".ttf", true) || name.endsWith(".otf", true) ||
                    // Carried, never read. A skin bundling an OFL font has to ship the licence with
                    // it to be redistributable, and dropping the file on import would quietly make
                    // every re-share of that skin non-compliant.
                    name.equals("LICENSE", true) || name.endsWith(".txt", true) || name.endsWith(".md", true)
                if (!allowed) {
                    zip.closeEntry()
                    continue
                }
                val out = File(target, name)
                var written = 0L
                out.outputStream().buffered().use { sink ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = zip.read(buf)
                        if (n <= 0) break
                        written += n
                        total += n
                        if (written > MAX_ENTRY_BYTES) throw SkinFormatException("'$name' is larger than ${MAX_ENTRY_BYTES / 1024 / 1024} MB.")
                        if (total > MAX_TOTAL_BYTES) throw SkinFormatException("Bundle is larger than ${MAX_TOTAL_BYTES / 1024 / 1024} MB.")
                        sink.write(buf, 0, n)
                    }
                }
                zip.closeEntry()
            }
        }
    }

    companion object {

        /** A generous font is ~2 MB; this leaves room without letting an archive run away. */
        const val MAX_ENTRY_BYTES = 8L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 12L * 1024 * 1024

        const val FILE_EXTENSION = "aapsskin"
        const val MIME_TYPE = "application/zip"
    }
}
