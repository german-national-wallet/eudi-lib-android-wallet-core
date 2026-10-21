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

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.nimbusds.oauth2.sdk.`as`.AuthorizationServerMetadata
import eu.europa.ec.eudi.openid4vci.CIAuthorizationServerMetadata
import eu.europa.ec.eudi.openid4vci.ClientAuthentication
import eu.europa.ec.eudi.openid4vci.CredentialIssuerId
import eu.europa.ec.eudi.openid4vci.CredentialIssuerMetadata
import eu.europa.ec.eudi.openid4vci.CredentialOffer
import eu.europa.ec.eudi.openid4vci.HttpsUrl
import eu.europa.ec.eudi.openid4vci.IssuerMetadataPolicy
import eu.europa.ec.eudi.openid4vci.Issuer
import eu.europa.ec.eudi.openid4vci.OpenId4VCIConfig
import eu.europa.ec.eudi.openid4vci.Signer
import eu.europa.ec.eudi.wallet.issue.openid4vci.dpop.DPopConfig
import eu.europa.ec.eudi.wallet.provider.WalletAttestationKey
import eu.europa.ec.eudi.wallet.provider.WalletInstanceAttestationProvider
import eu.europa.ec.eudi.wallet.provider.WalletKeyManager
import io.ktor.client.HttpClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.multipaz.crypto.Algorithm
import org.multipaz.securearea.KeyInfo
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * WD-4845: issuance with a wallet instance attestation (WIA) supplied by the caller (the app's
 * wallet backend WIA) instead of one fetched through the [WalletInstanceAttestationProvider].
 */
class IssuerCreatorSuppliedAttestationTest {

    private val makeConfig = slot<OpenId4VCIConfig>()
    private val metadataPolicy = slot<IssuerMetadataPolicy>()

    private val provider = mockk<WalletInstanceAttestationProvider>()
    private val walletKeyManager = mockk<WalletKeyManager>()
    private val popSigner = mockk<Signer<JWK>>()

    private val authorizationServerMetadata = authorizationServerMetadata(AS_URL)

    @BeforeTest
    fun setUp() {
        mockkObject(Issuer.Companion)
        // Only the config handed to the library matters here; stop before any network use.
        coEvery {
            Issuer.make(capture(makeConfig), any<CredentialOffer>(), any(), any(), any())
        } returns Result.failure(IssuerMadeException())
    }

    @AfterTest
    fun tearDown() {
        unmockkObject(Issuer.Companion)
    }

    @Test
    fun `supplied WIA authenticates the client instead of the configured provider`() = runTest {
        val wia = signedWia(subject = WIA_SUBJECT)

        assertFailsWith<IssuerMadeException> {
            issuerCreator(attestationBasedConfig()).createIssuerWithAttestation(offer(), wia, popSigner)
        }

        val auth = assertIs<ClientAuthentication.AttestationBased>(makeConfig.captured.clientAuthentication)
        val provisioned = auth.provisionClientAttestation(HttpsUrl(AS_URL).getOrThrow(), null)
        assertEquals(wia.serialize(), provisioned.clientAttestation.value)
        assertSame(popSigner, provisioned.popSigner)
        coVerify(exactly = 0) { provider.getWalletAttestation(any()) }
        coVerify(exactly = 0) { walletKeyManager.getOrCreateWalletAttestationKey(any(), any()) }
    }

    @Test
    fun `supplied WIA is handed out unchanged on every provisioning call`() = runTest {
        val wia = signedWia(subject = WIA_SUBJECT)

        assertFailsWith<IssuerMadeException> {
            issuerCreator(noneConfig()).createIssuerWithAttestation(offer(), wia, popSigner)
        }

        val auth = assertIs<ClientAuthentication.AttestationBased>(makeConfig.captured.clientAuthentication)
        val server = HttpsUrl(AS_URL).getOrThrow()
        val first = auth.provisionClientAttestation(server, null)
        val second = auth.provisionClientAttestation(server, null)
        assertEquals(first.clientAttestation, second.clientAttestation)
    }

    @Test
    fun `client id is the supplied WIA's subject, not the configured client id`() = runTest {
        assertFailsWith<IssuerMadeException> {
            issuerCreator(noneConfig()).createIssuerWithAttestation(offer(), signedWia(subject = WIA_SUBJECT), popSigner)
        }

        assertEquals(WIA_SUBJECT, makeConfig.captured.clientAuthentication.id)
    }

    @Test
    fun `supplied WIA without a subject is rejected before the issuer is made`() = runTest {
        val error = assertFailsWith<IllegalArgumentException> {
            issuerCreator(noneConfig()).createIssuerWithAttestation(offer(), signedWia(subject = null), popSigner)
        }

        assertEquals("Client attestation JWT is missing the 'sub' (client id) claim", error.message)
        coVerify(exactly = 0) { Issuer.make(any(), any<CredentialOffer>(), any(), any(), any()) }
    }

    @Test
    fun `offer issuance without a supplied WIA still asks the configured provider`() = runTest {
        stubProviderDrivenAttestation()

        assertFailsWith<IssuerMadeException> {
            issuerCreator(attestationBasedConfig()).createIssuer(offer())
        }

        assertIs<ClientAuthentication.AttestationBased>(makeConfig.captured.clientAuthentication)
        assertEquals(PROVIDER_WIA_SUBJECT, makeConfig.captured.clientAuthentication.id)
        coVerify(exactly = 1) { provider.getWalletAttestation(any()) }
    }

    @Test
    fun `supplied WIA clears the PoP key id left by an earlier provider-driven issuance`() = runTest {
        stubProviderDrivenAttestation()
        val issuerCreator = issuerCreator(attestationBasedConfig())
        assertFailsWith<IssuerMadeException> { issuerCreator.createIssuer(offer()) }
        assertEquals(PROVIDER_KEY_ALIAS, issuerCreator.clientAttestationPopKeyId)

        assertFailsWith<IssuerMadeException> {
            issuerCreator.createIssuerWithAttestation(offer(), signedWia(subject = WIA_SUBJECT), popSigner)
        }

        assertNull(issuerCreator.clientAttestationPopKeyId)
    }

    @Test
    fun `resolveAuthorizationServerMetadata returns the issuer's first authorization server`() = runTest {
        val second = authorizationServerMetadata("https://second-as.example.com")
        coEvery {
            Issuer.metaData(any(), CredentialIssuerId(ISSUER_URL).getOrThrow(), capture(metadataPolicy))
        } returns (mockk<CredentialIssuerMetadata>(relaxed = true) to listOf(authorizationServerMetadata, second))

        val result = issuerCreator(noneConfig(), IssuerMetadataPolicy.IgnoreSigned)
            .resolveAuthorizationServerMetadata(ISSUER_URL)

        assertSame(authorizationServerMetadata, result)
        assertEquals(IssuerMetadataPolicy.IgnoreSigned, metadataPolicy.captured)
    }

    @Test
    fun `resolveAuthorizationServerMetadata propagates a metadata failure`() = runTest {
        coEvery { Issuer.metaData(any(), CredentialIssuerId(ISSUER_URL).getOrThrow(), any()) } throws IllegalStateException("metadata unavailable")

        val error = assertFailsWith<IllegalStateException> {
            issuerCreator(noneConfig()).resolveAuthorizationServerMetadata(ISSUER_URL)
        }

        assertEquals("metadata unavailable", error.message)
    }

    private fun stubProviderDrivenAttestation() {
        val keyInfo = mockk<KeyInfo>(relaxed = true) {
            every { alias } returns PROVIDER_KEY_ALIAS
            every { algorithm } returns Algorithm.ESP256
        }
        coEvery { walletKeyManager.getOrCreateWalletAttestationKey(any(), any()) } returns
            Result.success(WalletAttestationKey(keyInfo) { it })
        coEvery { provider.getWalletAttestation(any()) } returns
            Result.success(signedWia(subject = PROVIDER_WIA_SUBJECT).serialize())
    }

    private fun issuerCreator(
        config: OpenId4VciManager.Config,
        issuerMetadataPolicy: IssuerMetadataPolicy = IssuerMetadataPolicy.IgnoreSigned,
    ) = IssuerCreator(
        context = mockk(relaxed = true),
        config = config,
        ktorHttpClientFactory = { mockk<HttpClient>(relaxed = true) },
        walletInstanceAttestationProvider = provider,
        walletAttestationKeyManager = walletKeyManager,
        logger = null,
        issuerMetadataPolicy = issuerMetadataPolicy,
    )

    private fun noneConfig() = config(OpenId4VciManager.ClientAuthenticationType.None(CONFIGURED_CLIENT_ID))

    private fun attestationBasedConfig() =
        config(OpenId4VciManager.ClientAuthenticationType.AttestationBased(CONFIGURED_CLIENT_ID))

    private fun config(clientAuthenticationType: OpenId4VciManager.ClientAuthenticationType) =
        OpenId4VciManager.Config(
            clientAuthenticationType = clientAuthenticationType,
            authFlowRedirectionURI = "app://redirect",
            dpopConfig = DPopConfig.Disabled,
        )

    private fun offer() = Offer(
        credentialOffer = mockk<CredentialOffer>(relaxed = true) {
            every { authorizationServerMetadata } returns this@IssuerCreatorSuppliedAttestationTest.authorizationServerMetadata
        },
    )

    private class IssuerMadeException : RuntimeException("Issuer.make reached")

    private companion object {
        const val ISSUER_URL = "https://issuer.example.com"
        const val AS_URL = "https://as.example.com"
        const val CONFIGURED_CLIENT_ID = "configured-client-id"
        const val WIA_SUBJECT = "wallet-backend-client-id"
        const val PROVIDER_WIA_SUBJECT = "provider-client-id"
        const val PROVIDER_KEY_ALIAS = "provider-wia-key"

        val wiaSigningKey = ECKeyGenerator(Curve.P_256).generate()

        fun signedWia(subject: String?): SignedJWT =
            SignedJWT(
                JWSHeader(JWSAlgorithm.ES256),
                JWTClaimsSet.Builder().apply { subject?.let { subject(it) } }.issuer("wallet-provider").build(),
            ).apply { sign(ECDSASigner(wiaSigningKey)) }

        fun authorizationServerMetadata(issuer: String): CIAuthorizationServerMetadata =
            AuthorizationServerMetadata.parse(
                """
                {
                  "issuer": "$issuer",
                  "token_endpoint": "$issuer/token",
                  "token_endpoint_auth_methods_supported": ["attest_jwt_client_auth"],
                  "client_attestation_pop_signing_alg_values_supported": ["ES256"]
                }
                """.trimIndent(),
            )
    }
}
