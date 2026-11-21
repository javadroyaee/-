package ir.jrweb.avangvpn

data class VpnConfig(
    val id: String, val name: String, val protocol: String, val host: String, val port: Int, val rawUri: String,
    var pingMs: Int = -1, var isSelected: Boolean = false
)

data class SubscriptionInfo(
    var url: String = "", var title: String = "Ú¯Ø±ÙˆÙ‡ Ø§Ø´ØªØ±Ø§Ú© Ø¢ÙˆÙ†Ú¯", var usedBytes: Long = 0L, var totalBytes: Long = 0L, var expireTimestamp: Long = 0L
)