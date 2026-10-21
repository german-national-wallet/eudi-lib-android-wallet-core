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
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.nimbusds.oauth2.sdk.`as`.AuthorizationServerMetadata
import eu.europa.ec.eudi.openid4vci.CIAuthorizationServerMetadata
import eu.europa.ec.eudi.openid4vci.ClientAuthentication
import eu.europa.ec.eudi.openid4vci.CredentialOffer
import eu.europa.ec.eudi.openid4vci.CredentialIssuerId
import eu.europa.ec.eudi.openid4vci.CredentialIssuerMetadata
import eu.europa.ec.eudi.openid4vci.HttpsUrl
import eu.europa.ec.eudi.openid4vci.Issuer
import eu.europa.ec.eudi.openid4vci.OpenId4VCIConfig
import eu.europa.ec.eudi.wallet.issue.openid4vci.dpop.DPopConfig
import eu.europa.ec.eudi.wallet.provider.WalletAttestationsProvider
import io.ktor.client.HttpClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executor
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * WD-4845: the manager APIs the app uses to issue EAAs with its wallet backend WIA.
 */
class DefaultOpenId4VciManagerSuppliedAttestationTest {

    private val makeConfig = slot<OpenId4VCIConfig>()
    private val provider = mockk<WalletAttestationsProvider>()
    private val authorizationServerMetadata = authorizationServerMetadata(AS_URL)

    @BeforeTest
    fun setUp() {
        mockkObject(Issuer.Companion)
    }

    @AfterTest
    fun tearDown() {
        unmockkObject(Issuer.Companion)
    }

    @Test
    fun `issueDocumentByOfferAttested authenticates with the supplied WIA and PoP key`() = runBlocking {
        val failure = IllegalStateException("stop after Issuer.make")
        coEvery {
            Issuer.make(capture(makeConfig), any<CredentialOffer>(), any(), any(), any())
        } returns Result.failure(failure)
        val wia = signedWia(WIA_SUBJECT)
        val popKey = ECKeyGenerator(Curve.P_256).generate()

        val event = issueByOfferAttested(wia, popKey)

        assertSame(failure, assertIs<IssueEvent.Failure>(event).cause)
        val auth = assertIs<ClientAuthentication.AttestationBased>(makeConfig.captured.clientAuthentication)
        assertEquals(WIA_SUBJECT, auth.id)
        val provisioned = auth.provisionClientAttestation(HttpsUrl(AS_URL).getOrThrow(), null)
        assertEquals(wia.serialize(), provisioned.clientAttestation.value)
        assertEquals(popKey.toPublicJWK(), provisioned.popSigner.acquire().publicMaterial)
        coVerify(exactly = 0) { provider.getWalletAttestation(any()) }
    }

    @Test
    fun `issueDocumentByOfferAttested reports a WIA without a subject as a failure`() = runBlocking {
        val event = issueByOfferAttested(signedWia(subject = null), ECKeyGenerator(Curve.P_256).generate())

        assertIs<IllegalArgumentException>(assertIs<IssueEvent.Failure>(event).cause)
        coVerify(exactly = 0) { Issuer.make(any(), any<CredentialOffer>(), any(), any(), any()) }
    }

    @Test
    fun `getAuthorizationServerMetadata returns the issuer's first authorization server`() = runTest {
        coEvery { Issuer.metaData(any(), ISSUER_ID, any()) } returns
            (mockk<CredentialIssuerMetadata>(relaxed = true) to listOf(authorizationServerMetadata, authorizationServerMetadata(OTHER_AS_URL)))

        val result = manager().getAuthorizationServerMetadata(ISSUER_URL)

        assertSame(authorizationServerMetadata, result.getOrThrow())
    }

    @Test
    fun `getAuthorizationServerMetadata wraps a metadata failure in the result`() = runTest {
        coEvery { Issuer.metaData(any(), ISSUER_ID, any()) } throws IllegalStateException("metadata unavailable")

        val result = manager().getAuthorizationServerMetadata(ISSUER_URL)

        assertTrue(result.isFailure)
        assertEquals("metadata unavailable", result.exceptionOrNull()?.message)
    }

    @Test
    fun `getAuthorizationServerMetadata rejects a non-https issuer url without fetching`() = runTest {
        coEvery { Issuer.metaData(any(), ISSUER_ID, any()) } throws AssertionError("metadata fetched")

        val result = manager().getAuthorizationServerMetadata("http://issuer.example.com")

        // Rejected while parsing the issuer id: the stubbed fetch for the https issuer is never reached.
        assertIs<IllegalArgumentException>(result.exceptionOrNull())
    }

    private suspend fun issueByOfferAttested(
        wia: SignedJWT,
        popKey: com.nimbusds.jose.jwk.ECKey,
    ): IssueEvent {
        val event = CompletableDeferred<IssueEvent>()
        manager().issueDocumentByOfferAttested(
            offer = offer(),
            walletAttestation = wia,
            walletWiaPopPublicKey = popKey.toPublicJWK(),
            walletWiaPopPrivateKey = popKey.toECPrivateKey(),
            executor = DIRECT_EXECUTOR,
            onIssueEvent = { event.complete(it) },
        )
        return withTimeout(TIMEOUT_MILLIS) { event.await() }
    }

    private fun manager() = DefaultOpenId4VciManager(
        context = mockk(relaxed = true),
        documentManager = mockk(relaxed = true),
        walletAttestationKeyManager = mockk(),
        walletProvider = provider,
        config = OpenId4VciManager.Config(
            clientAuthenticationType = OpenId4VciManager.ClientAuthenticationType.None(CONFIGURED_CLIENT_ID),
            authFlowRedirectionURI = "app://redirect",
            dpopConfig = DPopConfig.Disabled,
        ),
        ktorHttpClientFactory = { mockk<HttpClient>(relaxed = true) },
    )

    private fun offer() = Offer(
        credentialOffer = mockk<CredentialOffer>(relaxed = true) {
            every { authorizationServerMetadata } returns this@DefaultOpenId4VciManagerSuppliedAttestationTest.authorizationServerMetadata
        },
    )

    private companion object {
        const val ISSUER_URL = "https://issuer.example.com"

        // A concrete id: MockK cannot build a placeholder for this value class (its URL has no
        // stream handler), so an any() matcher fails while recording.
        val ISSUER_ID = CredentialIssuerId(ISSUER_URL).getOrThrow()
        const val AS_URL = "https://as.example.com"
        const val OTHER_AS_URL = "https://other-as.example.com"
        const val CONFIGURED_CLIENT_ID = "configured-client-id"
        const val WIA_SUBJECT = "wallet-backend-client-id"
        const val TIMEOUT_MILLIS = 5_000L

        val DIRECT_EXECUTOR = Executor { it.run() }
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
                  "token_endpoint_auth_methods_supported": ["attest_jwt_client_auth"]
                }
                """.trimIndent(),
            )
    }
}
