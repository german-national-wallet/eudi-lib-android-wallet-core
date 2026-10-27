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

import eu.europa.ec.eudi.iso18013.transfer.readerauth.CrlCache
import eu.europa.ec.eudi.iso18013.transfer.readerauth.ReaderTrustStoreImpl
import eu.europa.ec.eudi.iso18013.transfer.readerauth.RevocationPolicy
import eu.europa.ec.eudi.openid4vci.CertificateChainTrust
import eu.europa.ec.eudi.wallet.internal.d
import eu.europa.ec.eudi.wallet.internal.e
import eu.europa.ec.eudi.wallet.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.cert.X509Certificate
import kotlin.time.Duration

/**
 * A [CertificateChainTrust] that validates a certificate chain with PKIX against a fixed set of
 * X.509 trust anchors, for use without the ETSI trust infrastructure.
 *
 * Typical use is validating the `x5c` chain of signed issuer metadata:
 * ```
 * OpenId4VciManager.Config.Builder()
 *     .withIssuerMetadataPolicy(
 *         IssuerMetadataPolicy.RequireSigned(X509CertificateChainTrust(issuerCaCertificates, RevocationPolicy.SoftFail))
 *     )
 * ```
 *
 * Validation, including the CRL downloads and the timeout, is the one [ReaderTrustStoreImpl]
 * performs for reader authentication.
 *
 * @param trustAnchors the certificates accepted as trust anchors; must not be empty
 * @param revocationPolicy the certificate revocation checking policy
 * @param revocationCheckTimeout the upper bound for a validation, including any CRL download
 * @param logger optional [Logger] for diagnostic output
 * @param crlCache the cache that keeps the downloaded CRLs between validations, or `null` to download them every time
 */
class X509CertificateChainTrust @JvmOverloads constructor(
    trustAnchors: List<X509Certificate>,
    revocationPolicy: RevocationPolicy,
    revocationCheckTimeout: Duration = ReaderTrustStoreImpl.DEFAULT_REVOCATION_CHECK_TIMEOUT,
    private val logger: Logger? = null,
    crlCache: CrlCache? = null,
) : CertificateChainTrust {

    init {
        require(trustAnchors.isNotEmpty()) { "trustAnchors must not be empty" }
    }

    private val trustStore = ReaderTrustStoreImpl(
        trustedCertificates = trustAnchors,
        profileValidation = { _, _ -> true },
        errorLogger = { tag, message, cause -> logger?.e(TAG, "$tag: $message", cause) },
        revocationPolicy = revocationPolicy,
        revocationCheckTimeout = revocationCheckTimeout,
        crlCache = crlCache,
    )

    override suspend fun isTrusted(chain: List<X509Certificate>): Boolean {
        if (chain.isEmpty()) return false
        val trusted = withContext(Dispatchers.IO) { trustStore.validateCertificationTrustPath(chain) }
        logger?.d(TAG, "isTrusted: leaf=${chain.first().subjectX500Principal}, result=$trusted")
        return trusted
    }

    private companion object {
        const val TAG = "X509CertificateChainTrust"
    }
}
