package app.aaps.plugins.main.general.smsCommunicator.compose

/** Presentation state for the redesigned SMS & remote screen (handoff Section 7 — SMS & remote). */
data class SmsUiState(
    val remoteCommandsOn: Boolean = false,
    val otpOn: Boolean = false,
    val allowedNumbers: List<String> = emptyList(),
    val messages: List<SmsLine> = emptyList()
)

data class SmsLine(
    val time: String,
    val incoming: Boolean,
    val processed: Boolean,
    val ignored: Boolean,
    val number: String,
    val text: String
)
