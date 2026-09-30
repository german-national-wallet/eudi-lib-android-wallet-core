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
import eu.europa.ec.eudi.openid4vci.CredentialIssuerMetadataResolver
import eu.europa.ec.eudi.openid4vci.IssuerMetadataPolicy
import eu.europa.ec.eudi.wallet.issue.openid4vci.dpop.DPopConfig
import eu.europa.ec.eudi.wallet.trust.IssuerTrustConfig
import io.ktor.client.HttpClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultOpenId4VciManagerMetadataPolicyTest {

    private val requested = mutableListOf<IssuerMetadataPolicy>()

    private val configPolicy = IssuerMetadataPolicy.RequireSigned(CertificateChainTrust { true })
    private val trustConfigPolicy = IssuerMetadataPolicy.PreferSigned(CertificateChainTrust { true })

    @BeforeTest
    fun setUp() {
        mockkObject(CredentialIssuerMetadataResolver.Companion)
        every { CredentialIssuerMetadataResolver.Companion.invoke(any()) } returns
            CredentialIssuerMetadataResolver { _, policy ->
                requested += policy
                Result.failure(IllegalStateException("not resolved in test"))
            }
    }

    @AfterTest
    fun tearDown() {
        unmockkObject(CredentialIssuerMetadataResolver.Companion)
    }

    @Test
    fun `config policy takes precedence over the issuer trust policy`() = runTest {
        manager(configPolicy = configPolicy, trustConfigPolicy = trustConfigPolicy)
            .getIssuerMetadata(ISSUER_URL)

        assertEquals(listOf<IssuerMetadataPolicy>(configPolicy), requested)
    }

    @Test
    fun `issuer trust policy applies when the config sets none`() = runTest {
        manager(configPolicy = null, trustConfigPolicy = trustConfigPolicy)
            .getIssuerMetadata(ISSUER_URL)

        assertEquals(listOf<IssuerMetadataPolicy>(trustConfigPolicy), requested)
    }

    @Test
    fun `signed metadata is ignored when no policy is configured`() = runTest {
        manager(configPolicy = null, trustConfigPolicy = null)
            .getIssuerMetadata(ISSUER_URL)

        assertEquals(listOf<IssuerMetadataPolicy>(IssuerMetadataPolicy.IgnoreSigned), requested)
    }

    private fun manager(
        configPolicy: IssuerMetadataPolicy?,
        trustConfigPolicy: IssuerMetadataPolicy?,
    ) = DefaultOpenId4VciManager(
        context = mockk(relaxed = true),
        documentManager = mockk(relaxed = true),
        walletAttestationKeyManager = mockk(relaxed = true),
        config = OpenId4VciManager.Config(
            clientAuthenticationType = OpenId4VciManager.ClientAuthenticationType.None("client-id"),
            authFlowRedirectionURI = "app://redirect",
            dpopConfig = DPopConfig.Disabled,
            issuerMetadataPolicy = configPolicy,
        ),
        ktorHttpClientFactory = { mockk<HttpClient>(relaxed = true) },
        issuerTrustConfig = trustConfigPolicy?.let { policy ->
            mockk<IssuerTrustConfig> { every { issuerMetadataPolicy } returns policy }
        },
    )

    private companion object {
        const val ISSUER_URL = "https://issuer.example.com"
    }
}
