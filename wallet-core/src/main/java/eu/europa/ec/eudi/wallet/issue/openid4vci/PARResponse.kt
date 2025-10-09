package eu.europa.ec.eudi.wallet.issue.openid4vci

import java.net.URL

data class PARResponse (
    val authorizationCodeURL: URL,
    val state: String,
)
