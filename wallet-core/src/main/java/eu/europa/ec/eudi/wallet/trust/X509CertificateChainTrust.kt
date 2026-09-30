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

package eu.europa.ec.eudi.wallet.trust

import eu.europa.ec.eudi.etsi1196x2.consultation.CertificationChainValidation
import eu.europa.ec.eudi.etsi1196x2.consultation.NonEmptyList
import eu.europa.ec.eudi.etsi1196x2.consultation.ValidateCertificateChainUsingPKIXJvm
import eu.europa.ec.eudi.openid4vci.CertificateChainTrust
import eu.europa.ec.eudi.wallet.internal.d
import eu.europa.ec.eudi.wallet.internal.e
import eu.europa.ec.eudi.wallet.logging.Logger
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate

/**
 * A [CertificateChainTrust] that validates a certificate chain with PKIX against a fixed set of
 * X.509 trust anchors, for use without the ETSI trust infrastructure.
 *
 * Typical use is validating the `x5c` chain of signed issuer metadata:
 * ```
 * OpenId4VciManager.Config.Builder()
 *     .withIssuerMetadataPolicy(
 *         IssuerMetadataPolicy.RequireSigned(X509CertificateChainTrust(issuerCaCertificates, checkRevocation = false))
 *     )
 * ```
 *
 * @param trustAnchors the certificates accepted as trust anchors; must not be empty
 * @param checkRevocation whether PKIX revocation checking is enabled. The platform PKIX validator
 *   does not download CRLs from the certificates' distribution points, so with this enabled a
 *   chain is only trusted when its revocation status is otherwise available to the validator.
 * @param logger optional [Logger] for diagnostic output
 */
class X509CertificateChainTrust @JvmOverloads constructor(
    trustAnchors: List<X509Certificate>,
    checkRevocation: Boolean,
    private val logger: Logger? = null,
) : CertificateChainTrust {

    private val anchors: NonEmptyList<TrustAnchor> = requireNotNull(
        NonEmptyList.nelOrNull(trustAnchors.map { TrustAnchor(it, null) })
    ) { "trustAnchors must not be empty" }

    private val validateChain = ValidateCertificateChainUsingPKIXJvm(
        customization = { isRevocationEnabled = checkRevocation },
    )

    override suspend fun isTrusted(chain: List<X509Certificate>): Boolean {
        if (chain.isEmpty()) return false
        return try {
            val result = validateChain(chain, anchors)
            logger?.d(TAG, "isTrusted: leaf=${chain.first().subjectX500Principal}, result=$result")
            result is CertificationChainValidation.Trusted
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger?.e(TAG, "isTrusted failed: ${e.message}", e)
            false
        }
    }

    private companion object {
        const val TAG = "X509CertificateChainTrust"
    }
}
