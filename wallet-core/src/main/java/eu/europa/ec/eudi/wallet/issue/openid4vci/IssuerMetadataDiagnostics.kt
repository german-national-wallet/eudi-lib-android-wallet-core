/*
 * Copyright (c) 2026 European Commission
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.europa.ec.eudi.wallet.issue.openid4vci

import eu.europa.ec.eudi.openid4vci.CredentialIssuerMetadataError
import eu.europa.ec.eudi.openid4vci.CredentialIssuerMetadataValidationError
import eu.europa.ec.eudi.openid4vci.CredentialOfferRequestError
import eu.europa.ec.eudi.openid4vci.CredentialOfferRequestException
import eu.europa.ec.eudi.openid4vci.IssuerMetadataPolicy
import eu.europa.ec.eudi.wallet.internal.e
import eu.europa.ec.eudi.wallet.logging.Logger

/**
 * Logs why the credential issuer's metadata was rejected, when [error] was caused by it.
 *
 * openid4vci reports this as nested error types without a message, and
 * [CredentialOfferRequestException] keeps the reason outside the cause chain, so neither the
 * error shown to the user nor its stack trace names it.
 */
internal fun Logger.logIssuerMetadataFailure(
    tag: String,
    policy: IssuerMetadataPolicy,
    error: Throwable,
) {
    val metadataError = error.causes().firstNotNullOfOrNull { it as? CredentialIssuerMetadataError } ?: return
    e(tag, "Issuer metadata rejected under ${policy::class.simpleName}: ${metadataError.reason()}", metadataError)
}

private fun Throwable.causes(): Sequence<Throwable> =
    generateSequence(this) { current ->
        when (current) {
            is CredentialOfferRequestException -> current.error.reason()
            else -> current.cause
        }?.takeIf { it !== current }
    }.take(MAX_CAUSE_DEPTH)

private fun CredentialOfferRequestError.reason(): Throwable? = when (this) {
    is CredentialOfferRequestError.UnableToResolveCredentialIssuerMetadata -> reason
    is CredentialOfferRequestError.UnableToResolveAuthorizationServerMetadata -> reason
    else -> null
}

private fun CredentialIssuerMetadataError.reason(): String = when (this) {
    is CredentialIssuerMetadataError.MissingSignedMetadata ->
        "the issuer did not answer the request for signed metadata with application/jwt"
    is CredentialIssuerMetadataError.InvalidSignedMetadata ->
        "the signed metadata failed verification (signature, typ, algorithm or untrusted x5c chain): ${cause.describe()}"
    is CredentialIssuerMetadataError.UnableToFetchCredentialIssuerMetadata ->
        "the metadata could not be fetched: ${cause.describe()}"
    is CredentialIssuerMetadataError.NonParseableCredentialIssuerMetadata ->
        "the metadata could not be parsed: ${cause.describe()}"
    is CredentialIssuerMetadataValidationError ->
        "the metadata is invalid (${this::class.simpleName}): ${cause.describe()}"
}

private fun Throwable?.describe(): String =
    this?.causes()?.joinToString(" <- ") { "${it::class.simpleName}: ${it.message}" } ?: "no cause"

private const val MAX_CAUSE_DEPTH = 10
