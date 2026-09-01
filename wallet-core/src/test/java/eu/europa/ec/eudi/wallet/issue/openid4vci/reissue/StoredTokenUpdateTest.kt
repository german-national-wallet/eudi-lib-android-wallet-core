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

package eu.europa.ec.eudi.wallet.issue.openid4vci.reissue

import eu.europa.ec.eudi.openid4vci.AccessToken
import eu.europa.ec.eudi.openid4vci.AuthorizedRequest
import eu.europa.ec.eudi.openid4vci.Grant
import eu.europa.ec.eudi.openid4vci.RefreshToken
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import org.multipaz.storage.Storage
import org.multipaz.storage.ephemeral.EphemeralStorage
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Covers the WD-3753 fix: a refresh's tokens reach storage immediately, on every affected row. */
class StoredTokenUpdateTest {

    private fun metadata(
        accessToken: String = "old-access",
        accessTokenType: String = "DPoP",
        refreshToken: String? = "old-refresh",
        tokenTimestamp: Long = 1_700_000_000L,
        configurationIdentifier: String = "pid-mso-mdoc",
    ) = IssuanceMetadata(
        credentialIssuerId = "https://issuer.example.com",
        credentialConfigurationIdentifier = configurationIdentifier,
        credentialEndpoint = "https://issuer.example.com/credential",
        tokenEndpoint = "https://auth.example.com/token",
        authorizationServerId = "https://auth.example.com",
        clientId = "wallet-client",
        popKeyAliases = listOf("key-alias-1"),
        accessToken = accessToken,
        accessTokenType = accessTokenType,
        refreshToken = refreshToken,
        tokenTimestamp = tokenTimestamp,
        grantType = "authorization_code",
    )

    private fun refreshed(
        accessToken: AccessToken = AccessToken.DPoP("new-access", expiresIn = null),
        refreshToken: String? = "new-refresh",
        timestamp: Instant = Instant.ofEpochSecond(1_700_009_999L),
    ) = AuthorizedRequest(
        accessToken = accessToken,
        refreshToken = refreshToken?.let { RefreshToken(it) },
        credentialIdentifiers = emptyMap(),
        timestamp = timestamp,
        authorizationServerDpopNonce = null,
        resourceServerDpopNonce = null,
        grant = Grant.AuthorizationCode,
    )

    private suspend fun Storage.put(documentId: String, metadata: IssuanceMetadata) {
        getTable(IssuanceMetadata.STORAGE_TABLE_SPEC)
            .insert(documentId, ByteString(metadata.toByteArray()))
    }

    private suspend fun Storage.read(documentId: String): IssuanceMetadata =
        IssuanceMetadata.fromByteArray(
            getTable(IssuanceMetadata.STORAGE_TABLE_SPEC).get(documentId)!!.toByteArray()
        )

    @Test
    fun `writes the refreshed tokens onto the refreshed document's own row`() = runTest {
        val storage = EphemeralStorage()
        storage.put("pid-mdoc", metadata())

        val updated = storage.updateStoredTokens(
            documentId = "pid-mdoc",
            consumedRefreshToken = "old-refresh",
            refreshed = refreshed(),
        )

        assertEquals(listOf("pid-mdoc"), updated)
        with(storage.read("pid-mdoc")) {
            assertEquals("new-access", accessToken)
            assertEquals("DPoP", accessTokenType)
            assertEquals("new-refresh", refreshToken)
            assertEquals(1_700_009_999L, tokenTimestamp)
        }
    }

    @Test
    fun `also updates the sibling row issued from the same authorization session`() = runTest {
        // A PID is two documents minted from one /token call: both rows hold the same token pair.
        val storage = EphemeralStorage()
        storage.put("pid-mdoc", metadata(configurationIdentifier = "pid-mso-mdoc"))
        storage.put("pid-sdjwt", metadata(configurationIdentifier = "pid-sd-jwt"))

        val updated = storage.updateStoredTokens(
            documentId = "pid-mdoc",
            consumedRefreshToken = "old-refresh",
            refreshed = refreshed(),
        )

        assertEquals(setOf("pid-mdoc", "pid-sdjwt"), updated.toSet())
        assertEquals("new-refresh", storage.read("pid-sdjwt").refreshToken)
        // The sibling keeps its own credential configuration — only the tokens are rewritten.
        assertEquals("pid-sd-jwt", storage.read("pid-sdjwt").credentialConfigurationIdentifier)
    }

    @Test
    fun `leaves rows from a different authorization session untouched`() = runTest {
        val storage = EphemeralStorage()
        storage.put("pid-mdoc", metadata())
        storage.put("diploma", metadata(refreshToken = "unrelated-refresh"))

        val updated = storage.updateStoredTokens(
            documentId = "pid-mdoc",
            consumedRefreshToken = "old-refresh",
            refreshed = refreshed(),
        )

        assertTrue("diploma" !in updated)
        with(storage.read("diploma")) {
            assertEquals("unrelated-refresh", refreshToken)
            assertEquals("old-access", accessToken)
        }
    }

    @Test
    fun `keeps the stored refresh token when the server did not rotate one`() = runTest {
        val storage = EphemeralStorage()
        storage.put("pid-mdoc", metadata())

        storage.updateStoredTokens(
            documentId = "pid-mdoc",
            consumedRefreshToken = "old-refresh",
            refreshed = refreshed(refreshToken = null),
        )

        with(storage.read("pid-mdoc")) {
            assertEquals("old-refresh", refreshToken)
            assertEquals("new-access", accessToken)
        }
    }

    @Test
    fun `records the access token type of the refreshed token`() = runTest {
        val storage = EphemeralStorage()
        storage.put("doc", metadata(accessTokenType = "DPoP"))

        storage.updateStoredTokens(
            documentId = "doc",
            consumedRefreshToken = "old-refresh",
            refreshed = refreshed(
                accessToken = AccessToken.Bearer("bearer-access", expiresIn = null)
            ),
        )

        assertEquals("Bearer", storage.read("doc").accessTokenType)
    }

    @Test
    fun `updates only the named row when there was no refresh token to rotate`() = runTest {
        val storage = EphemeralStorage()
        storage.put("doc", metadata(refreshToken = null))
        storage.put("other", metadata(refreshToken = null))

        val updated = storage.updateStoredTokens(
            documentId = "doc",
            consumedRefreshToken = null,
            refreshed = refreshed(),
        )

        assertEquals(listOf("doc"), updated)
        assertEquals(null, storage.read("other").refreshToken)
    }
}