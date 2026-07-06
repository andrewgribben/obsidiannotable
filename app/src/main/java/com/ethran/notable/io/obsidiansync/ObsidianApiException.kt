package com.ethran.notable.io.obsidiansync

/** Error response from the Obsidian REST API (`api.obsidian.md`). */
class ObsidianApiException(
    val statusCode: Int,
    val apiMessage: String = ""
) : Exception(formatMessage(statusCode, apiMessage)) {

    companion object {
        private fun formatMessage(statusCode: Int, apiMessage: String): String =
            if (apiMessage.isNotBlank()) "api: $apiMessage (HTTP $statusCode)"
            else "api: HTTP $statusCode"
    }
}
