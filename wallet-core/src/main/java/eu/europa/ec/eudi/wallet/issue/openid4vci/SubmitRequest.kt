/*
 * Copyright (c) 2024 European Commission
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

import com.android.identity.crypto.Algorithm
import com.android.identity.securearea.KeyUnlockData
import eu.europa.ec.eudi.openid4vci.AuthorizedRequest
import eu.europa.ec.eudi.openid4vci.ClaimName
import eu.europa.ec.eudi.openid4vci.GenericClaimSet
import eu.europa.ec.eudi.openid4vci.IssuanceRequestPayload
import eu.europa.ec.eudi.openid4vci.Issuer
import eu.europa.ec.eudi.openid4vci.Namespace
import eu.europa.ec.eudi.openid4vci.SubmissionOutcome
import eu.europa.ec.eudi.wallet.document.UnsignedDocument
import kotlinx.coroutines.runBlocking

internal class SubmitRequest(
    val config: OpenId4VciManager.Config,
    val issuer: Issuer,
    authorizedRequest: AuthorizedRequest,
    val algorithm: Algorithm = Algorithm.ES256,
) {
    var authorizedRequest: AuthorizedRequest = authorizedRequest
        private set

    /**
     * Request 1 document presentation at the moment,
     * This creates a relationship between the main PID document and the rest of credentials
     */
    suspend fun request(
        offeredDocuments: Map<UnsignedDocument, Offer.OfferedDocument>,
        offer: Offer
    ): Response {
        return Response(
            mapOf(
                Pair(
                    offeredDocuments.keys.first(), try {
                        Result.success(
                            submitRequest(
                                offeredDocuments,
                                offer = offer
                            )
                        )
                    } catch (e: Throwable) {
                        Result.failure(e)
                    }
                )
            )
        )
    }

    private suspend fun submitRequest(
        unsignedDocuments: Map<UnsignedDocument, Offer.OfferedDocument>,
        keyUnlockData: KeyUnlockData? = null,
        offer: Offer
    ): SubmissionOutcome {
        val offeredDocument = unsignedDocuments.values.first()
        val proofSigners: MutableList<JWSKeyPoPSigner> = mutableListOf()
        return try {
            val claimSet = null
            val payload = IssuanceRequestPayload.ConfigurationBased(
                offeredDocument.configurationIdentifier,
                claimSet
            )
            unsignedDocuments.forEach { entry ->
                proofSigners.add(
                    JWSKeyPoPSigner(
                        document = entry.key,
                        algorithm = algorithm,
                        keyUnlockData = keyUnlockData
                    )
                )
            }
            val (updatedAuthorizedRequest, outcome) = with(issuer) {
                authorizedRequest.request(payload, proofSigners.map { it.popSigner }.toList())
            }.getOrThrow()
            this.authorizedRequest = updatedAuthorizedRequest

            outcome
        } catch (e: Throwable) {
            if (null != proofSigners.first().keyLockedException) {
                throw UserAuthRequiredException(
                    signingAlgorithm = algorithm,
                    resume = { keyUnlockData ->
                        runBlocking {
                            submitRequest(
                                unsignedDocuments,
                                keyUnlockData,
                                offer = offer
                            )
                        }
                    },
                    cause = e
                )
            } else throw e
        }
    }

    class Response(map: Map<UnsignedDocument, Result<SubmissionOutcome>>) :
        Map<UnsignedDocument, Result<SubmissionOutcome>> by map
}