package eu.europa.ec.eudi.wallet.issue.openidvci

import java.net.URL

data class PARResponse (
    val authorizationCodeURL: URL,
    val state: String,
)