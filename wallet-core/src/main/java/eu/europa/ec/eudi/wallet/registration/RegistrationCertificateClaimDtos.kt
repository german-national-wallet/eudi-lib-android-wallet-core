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
package eu.europa.ec.eudi.wallet.registration

import android.annotation.SuppressLint
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * A multi-language string. Both `content` and `value` are accepted as the text field.
 */
@SuppressLint("UnsafeOptInUsageError")
@Serializable
internal data class MultiLangDto(
    val lang: String,
    val content: String? = null,
    val value: String? = null,
) {
    val text: String get() = content ?: value ?: ""
}

/**
 * Reads a multi-language registration certificate claim - `srv_description`, `purpose` - without
 * ever failing the certificate over its shape.
 *
 * Two reasons this cannot be a plain `List<MultiLangDto>`:
 *
 * 1. **The shape is not settled.** ETSI TS 119 475 / the EUDI TS5 profile describe
 *    `srv_description` in prose as "an array of arrays with localised descriptions" while giving its
 *    data type as "array of MultiLangString objects" in the same document, and `purpose` only as the
 *    latter. Issuers follow both readings: the EUDI playground sends `srv_description` nested and
 *    `purpose` flat, in one certificate. Committing to either shape rejects the issuers that chose
 *    the other, so both are accepted and a nested array is flattened.
 * 2. **Nothing decides anything on these claims.** They are display text. The binding check reads
 *    `identifiers`, over-asking reads `credentials`, revocation reads `status` - none of them touch
 *    this. A strict decode meant a relying party with an authentic, trusted, in-scope certificate
 *    was refused outright as [RegistrationFailureReason.MALFORMED] because a description it never
 *    even showed was one array deep. Anything unreadable here is therefore dropped rather than
 *    raised: an empty description is a cosmetic loss, a false rejection is not.
 *
 * Entries that are not objects, or that carry no `lang`, are skipped individually, so one bad entry
 * does not cost the rest.
 */
internal object MultiLangListSerializer :
    JsonTransformingSerializer<List<MultiLangDto>>(ListSerializer(MultiLangDto.serializer())) {

    override fun transformDeserialize(element: JsonElement): JsonElement {
        val entries = (element as? JsonArray) ?: return JsonArray(emptyList())
        // One level of nesting is unwrapped; each element is either an entry or a list of entries.
        return JsonArray(entries.flatMap { it.asEntries() }.filter { it.isUsableEntry() })
    }

    private fun JsonElement.asEntries(): List<JsonElement> = when (this) {
        is JsonArray -> this
        else -> listOf(this)
    }

    /** A `lang` is mandatory on a MultiLangString and is the one field with no sane default. */
    private fun JsonElement.isUsableEntry(): Boolean =
        this is JsonObject && (this["lang"] as? JsonPrimitive)?.isString == true
}

@SuppressLint("UnsafeOptInUsageError")
@Serializable
internal data class CredentialDto(
    val format: String,
    val meta: JsonObject? = null,
    val claim: List<ClaimDto> = emptyList(),
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
internal data class ClaimDto(
    val path: List<JsonPrimitive> = emptyList(),
    val values: List<JsonPrimitive>? = null,
)

internal fun MultiLangDto.toLocalizedText(): LocalizedText =
    LocalizedText(language = lang, value = text)

internal fun CredentialDto.toRegisteredCredential(): RegisteredCredential =
    RegisteredCredential(
        format = format,
        meta = meta.toCredentialMeta(),
        claims = claim.map { it.toRegisteredClaim() },
    )

internal fun CredentialDto.toProvidedAttestation(): ProvidedAttestation =
    ProvidedAttestation(
        format = format,
        meta = meta.toCredentialMeta(),
    )

private fun ClaimDto.toRegisteredClaim(): RegisteredClaim =
    RegisteredClaim(
        path = path.map { it.toClaimPathElement() },
        values = values?.map { it.content },
    )

/**
 * Reads one element of a registered claim path: `null` is the array wildcard, a non-negative integer
 * is an array index, and anything else is a claim name.
 */
private fun JsonPrimitive.toClaimPathElement(): ClaimPathElement = when {
    this is JsonNull -> ClaimPathElement.AllArrayElements
    !isString -> content.toIntOrNull()
        ?.takeIf { it >= 0 }
        ?.let { ClaimPathElement.ArrayElement(it) }
        ?: ClaimPathElement.Claim(content)

    else -> ClaimPathElement.Claim(content)
}

private fun JsonObject?.toCredentialMeta(): CredentialMeta? {
    if (this == null) return null
    return credentialMetaOrNull(
        doctypeValue = this["doctype_value"]?.jsonPrimitive?.contentOrNull,
        vctValues = this["vct_values"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull },
    )
}
