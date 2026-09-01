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
import kotlinx.io.bytestring.ByteString
import org.multipaz.storage.Storage

/**
 * Writes the tokens from a successful token refresh into the stored [IssuanceMetadata] (WD-3753).
 *
 * Updates the row for [documentId] and every other row still carrying [consumedRefreshToken]: one
 * authorization session can back several documents — a PID is issued as an mdoc *and* an SD-JWT VC
 * from a single `/token` call — so refreshing one rotates the token out from under its siblings.
 *
 * @return the ids of the rows that were updated
 */
internal suspend fun Storage.updateStoredTokens(
    documentId: String,
    consumedRefreshToken: String?,
    refreshed: AuthorizedRequest,
): List<String> {
    val table = getTable(IssuanceMetadata.STORAGE_TABLE_SPEC)
    val accessTokenType = when (refreshed.accessToken) {
        is AccessToken.DPoP -> "DPoP"
        is AccessToken.Bearer -> "Bearer"
    }

    val updatedIds = mutableListOf<String>()
    table.enumerateWithData().forEach { (key, bytes) ->
        val stored = runCatching { IssuanceMetadata.fromByteArray(bytes.toByteArray()) }.getOrNull()
            ?: return@forEach
        val sharesTokenSession = key == documentId ||
                (consumedRefreshToken != null && stored.refreshToken == consumedRefreshToken)
        if (!sharesTokenSession) return@forEach

        val updated = stored.copy(
            accessToken = refreshed.accessToken.accessToken,
            accessTokenType = accessTokenType,
            // Null only when there never was one: a server that does not rotate omits it and the
            // openid4vci library carries the previous one forward.
            refreshToken = refreshed.refreshToken?.refreshToken ?: stored.refreshToken,
            tokenTimestamp = refreshed.timestamp.epochSecond,
        )
        table.update(key, ByteString(updated.toByteArray()))
        updatedIds.add(key)
    }
    return updatedIds
}