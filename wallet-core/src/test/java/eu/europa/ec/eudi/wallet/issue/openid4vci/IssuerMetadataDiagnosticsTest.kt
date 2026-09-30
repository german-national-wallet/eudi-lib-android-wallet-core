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

import eu.europa.ec.eudi.openid4vci.CertificateChainTrust
import eu.europa.ec.eudi.openid4vci.CredentialIssuerMetadataError
import eu.europa.ec.eudi.openid4vci.CredentialOfferRequestError
import eu.europa.ec.eudi.openid4vci.CredentialOfferRequestException
import eu.europa.ec.eudi.openid4vci.IssuerMetadataPolicy
import eu.europa.ec.eudi.wallet.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IssuerMetadataDiagnosticsTest {

    private val records = mutableListOf<Logger.Record>()
    private val logger = Logger { records += it }
    private val policy = IssuerMetadataPolicy.RequireSigned(CertificateChainTrust { true })

    @Test
    fun `missing signed metadata inside an offer error is logged with the policy`() {
        val error = CredentialOfferRequestException(
            CredentialOfferRequestError.UnableToResolveCredentialIssuerMetadata(
                CredentialIssuerMetadataError.MissingSignedMetadata()
            )
        )

        logger.logIssuerMetadataFailure(TAG, policy, error)

        val record = records.single()
        assertEquals(Logger.LEVEL_ERROR, record.level)
        assertTrue("RequireSigned" in record.message, record.message)
        assertTrue("application/jwt" in record.message, record.message)
        assertIs<CredentialIssuerMetadataError.MissingSignedMetadata>(record.thrown)
    }

    @Test
    fun `invalid signed metadata names the verification cause`() {
        val error = RuntimeException(
            CredentialIssuerMetadataError.InvalidSignedMetadata(IllegalStateException("untrusted chain"))
        )

        logger.logIssuerMetadataFailure(TAG, policy, error)

        assertTrue("untrusted chain" in records.single().message, records.single().message)
    }

    @Test
    fun `errors unrelated to issuer metadata are not logged`() {
        logger.logIssuerMetadataFailure(TAG, policy, IllegalStateException("network down"))

        assertTrue(records.isEmpty())
    }

    private companion object {
        const val TAG = "test"
    }
}
