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

import android.content.Context
import com.nimbusds.oauth2.sdk.auth.ClientAuthenticationMethod
import com.nimbusds.oauth2.sdk.`as`.AuthorizationServerMetadata
import com.nimbusds.oauth2.sdk.id.Issuer
import eu.europa.ec.eudi.openid4vci.AttestationBasedClientAuthenticationSpec
import eu.europa.ec.eudi.openid4vci.CIAuthorizationServerMetadata
import eu.europa.ec.eudi.openid4vci.ClientAuthentication
import eu.europa.ec.eudi.wallet.provider.WalletInstanceAttestationProvider
import eu.europa.ec.eudi.wallet.provider.WalletKeyManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.multipaz.crypto.Algorithm
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * Covers [OpenId4VciManager.ClientAuthenticationType.AttestationIfSupported]: the wallet instance
 * attestation is used only when a provider is available and the authorization server advertises
 * `attest_jwt_client_auth` or client attestation PoP algorithms, decided per call from the given
 * metadata.
 */
class IssuerCreatorClientAuthenticationTest {

    private val clientId = "test-client-id"
    private val walletKeyManager: WalletKeyManager = mockk()

    // Marks that the attestation branch was taken without building a real attestation key.
    private val attestationBranchTaken = IllegalStateException("attestation branch")

    private fun issuerCreator(
        type: OpenId4VciManager.ClientAuthenticationType,
        provider: WalletInstanceAttestationProvider?,
    ): IssuerCreator {
        val config = OpenId4VciManager.Config.Builder()
            .withClientAuthenticationType(type)
            .withAuthFlowRedirectionURI("app://redirect")
            .build()
        coEvery { walletKeyManager.getOrCreateWalletAttestationKey(any(), any()) } returns
            Result.failure(attestationBranchTaken)
        return IssuerCreator(
            context = mockk<Context>(relaxed = true),
            config = config,
            ktorHttpClientFactory = { mockk() },
            walletInstanceAttestationProvider = provider,
            walletAttestationKeyManager = walletKeyManager,
            logger = null,
        )
    }

    private fun metadata(
        popAlgs: List<String>?,
        authMethods: List<String>? = null,
    ): CIAuthorizationServerMetadata =
        AuthorizationServerMetadata(Issuer("https://as.example.com")).apply {
            authMethods?.let { methods ->
                tokenEndpointAuthMethods = methods.map { ClientAuthenticationMethod.parse(it) }
            }
            popAlgs?.let {
                setCustomParameter(
                    AttestationBasedClientAuthenticationSpec.ATTESTATION_POP_JWT_SIGNING_ALGORITHMS_SUPPORTED,
                    it,
                )
            }
        }

    @Test
    fun `AttestationIfSupported uses attestation when provider is present and AS advertises PoP algs`() = runTest {
        val creator = issuerCreator(
            OpenId4VciManager.ClientAuthenticationType.AttestationIfSupported(clientId),
            mockk(),
        )

        val result = with(creator) { metadata(listOf("ES256")).toClientAuthentication() }

        assertSame(attestationBranchTaken, result.exceptionOrNull())
        coVerify(exactly = 1) { walletKeyManager.getOrCreateWalletAttestationKey("https://as.example.com", any()) }
    }

    @Test
    fun `AttestationIfSupported falls back to None when AS advertises no PoP algs`() = runTest {
        val creator = issuerCreator(
            OpenId4VciManager.ClientAuthenticationType.AttestationIfSupported(clientId),
            mockk(),
        )

        val auth = with(creator) { metadata(null).toClientAuthentication() }.getOrThrow()

        assertIs<ClientAuthentication.None>(auth)
        assertEquals(clientId, auth.id)
        assertEquals(null, creator.clientAttestationPopKeyId)
        coVerify(exactly = 0) { walletKeyManager.getOrCreateWalletAttestationKey(any(), any()) }
    }

    @Test
    fun `AttestationIfSupported falls back to None when AS advertises an empty PoP alg list`() = runTest {
        val creator = issuerCreator(
            OpenId4VciManager.ClientAuthenticationType.AttestationIfSupported(clientId),
            mockk(),
        )

        val auth = with(creator) { metadata(emptyList()).toClientAuthentication() }.getOrThrow()

        assertIs<ClientAuthentication.None>(auth)
        coVerify(exactly = 0) { walletKeyManager.getOrCreateWalletAttestationKey(any(), any()) }
    }

    @Test
    fun `AttestationIfSupported falls back to None when no attestation provider is available`() = runTest {
        val creator = issuerCreator(
            OpenId4VciManager.ClientAuthenticationType.AttestationIfSupported(clientId),
            provider = null,
        )

        val auth = with(creator) { metadata(listOf("ES256")).toClientAuthentication() }.getOrThrow()

        assertIs<ClientAuthentication.None>(auth)
        assertEquals(clientId, auth.id)
        coVerify(exactly = 0) { walletKeyManager.getOrCreateWalletAttestationKey(any(), any()) }
    }

    @Test
    fun `AttestationIfSupported uses attestation with ES256 when AS advertises only attest_jwt_client_auth`() = runTest {
        val creator = issuerCreator(
            OpenId4VciManager.ClientAuthenticationType.AttestationIfSupported(clientId),
            mockk(),
        )

        val result = with(creator) {
            metadata(
                popAlgs = null,
                authMethods = listOf(AttestationBasedClientAuthenticationSpec.ATTESTATION_JWT_CLIENT_AUTHENTICATION_METHOD),
            ).toClientAuthentication()
        }

        assertSame(attestationBranchTaken, result.exceptionOrNull())
        coVerify(exactly = 1) {
            walletKeyManager.getOrCreateWalletAttestationKey("https://as.example.com", listOf(Algorithm.fromJoseAlgorithmIdentifier("ES256")))
        }
    }

    @Test
    fun `AttestationIfSupported prefers advertised PoP algs over the ES256 fallback`() = runTest {
        val creator = issuerCreator(
            OpenId4VciManager.ClientAuthenticationType.AttestationIfSupported(clientId),
            mockk(),
        )

        with(creator) {
            metadata(
                popAlgs = listOf("ES384"),
                authMethods = listOf(AttestationBasedClientAuthenticationSpec.ATTESTATION_JWT_CLIENT_AUTHENTICATION_METHOD),
            ).toClientAuthentication()
        }

        coVerify(exactly = 1) {
            walletKeyManager.getOrCreateWalletAttestationKey("https://as.example.com", listOf(Algorithm.fromJoseAlgorithmIdentifier("ES384")))
        }
    }

    @Test
    fun `AttestationIfSupported falls back to None when AS advertises only other auth methods`() = runTest {
        val creator = issuerCreator(
            OpenId4VciManager.ClientAuthenticationType.AttestationIfSupported(clientId),
            mockk(),
        )

        val auth = with(creator) {
            metadata(popAlgs = null, authMethods = listOf("none", "private_key_jwt")).toClientAuthentication()
        }.getOrThrow()

        assertIs<ClientAuthentication.None>(auth)
        coVerify(exactly = 0) { walletKeyManager.getOrCreateWalletAttestationKey(any(), any()) }
    }

    @Test
    fun `AttestationIfSupported falls back to None without provider even if AS advertises attest_jwt_client_auth`() = runTest {
        val creator = issuerCreator(
            OpenId4VciManager.ClientAuthenticationType.AttestationIfSupported(clientId),
            provider = null,
        )

        val auth = with(creator) {
            metadata(
                popAlgs = null,
                authMethods = listOf(AttestationBasedClientAuthenticationSpec.ATTESTATION_JWT_CLIENT_AUTHENTICATION_METHOD),
            ).toClientAuthentication()
        }.getOrThrow()

        assertIs<ClientAuthentication.None>(auth)
        coVerify(exactly = 0) { walletKeyManager.getOrCreateWalletAttestationKey(any(), any()) }
    }

    @Test
    fun `AttestationBased still fails when AS advertises no PoP algs`() = runTest {
        val creator = issuerCreator(
            OpenId4VciManager.ClientAuthenticationType.AttestationBased(clientId),
            mockk(),
        )

        val result = with(creator) { metadata(null).toClientAuthentication() }

        assertIs<IllegalStateException>(result.exceptionOrNull())
        coVerify(exactly = 0) { walletKeyManager.getOrCreateWalletAttestationKey(any(), any()) }
    }
}
