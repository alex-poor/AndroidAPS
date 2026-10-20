package app.aaps.libre3

/**
 * The app-side certificate the phone presents to a Libre 3 during a FRESH PAIRING.
 *
 * Fresh pairing requires the app to write its own 162-byte certificate to
 * [Libre3Gatt.SEC_CHAR_CERT_DATA] *before* the sensor will return its patch certificate. Without
 * it the sensor ACKs the BEGIN_PAIRING command and then stays silent — which is exactly how AAPS
 * native pairing failed on hardware until this was wired in (resume was unaffected because it
 * skips the certificate exchange entirely).
 *
 * Two certificates, indexed by the sensor's security version (0 or 1, from the same selector used
 * for `selectAppKeyAndSavedAuthorization`). The bytes are constants baked into the vendor app —
 * extracted verbatim from Juggluco's `n50.b` (`tk.glucodata`), the array it writes at state R0==2
 * of `Libre3GattCallback`. They are the Abbott-issued app identity, not a per-sensor secret, so
 * they are the same for every phone. Byte 0 is 0x03; byte 33 is the 0x04 uncompressed-EC-point tag.
 */
object Libre3AppCertificate {

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }

    /** Index = security version. [forVersion] guards the bounds. */
    private val BY_VERSION: Array<ByteArray> = arrayOf(
        // version 0
        hex("03000102030405060708090a0b0c0d0e0f1000015f149fe1010000000000000000042751fd1ef4" +
            "2b145a52c593ae6b5a75588a9f7eaf1c0f9985f993d58f147bb84168422449963792dc43f3844" +
            "7efebbbeb4a53b3255c0be0fe1f235844a3d3299eba97b8e6c3170939f2778f64866f066deb91" +
            "5dd6629eee4730a1e14cab75c18c4fec53f8854c87643a764f4087aec0394c210c18865a8ff45" +
            "adc3727f48b53a7"),
        // version 1
        hex("03030102030405060708090a0b0c0d0e0f10000161897655010000000000000000048242be33f1" +
            "a330880112fa62cc4842a43d1204922ad201d8775bb226f611f75b0ef3d5bc6cc4317caa45758" +
            "4ab003f1712336089d3a4f29838ed0dc666deaea2d65a00dfff5d7bcae21655e302e3458e774d" +
            "aaaaca87af75f1b87884b18d4ce875d0d108c903a834471a4ff674b2d30bcba062373014b7786" +
            "e4437b177aec3c8")
    )

    /** Expected length of an app certificate on the wire. */
    const val LENGTH = 162

    /** The certificate for [securityVersion], or null if the version is unknown. */
    fun forVersion(securityVersion: Int): ByteArray? =
        BY_VERSION.getOrNull(securityVersion)?.also {
            require(it.size == LENGTH) { "app certificate $securityVersion is ${it.size} bytes, expected $LENGTH" }
        }
}
