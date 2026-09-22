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

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `srv_description` and `purpose` carry display text and decide nothing, but a strict decode of
 * their shape used to fail the whole certificate as MALFORMED.
 *
 * That was not hypothetical: the EUDI playground's relying party sends an authentic certificate,
 * signed by a trusted registrar and asking for exactly what it registered for, whose
 * `srv_description` is one array deeper than `purpose` in the very same payload. The wallet refused
 * it outright and told the user the service had no permission to request data.
 *
 * The shape is genuinely unsettled - the EUDI TS5 profile calls `srv_description` "an array of
 * arrays" in prose and "array of MultiLangString objects" in its data type column - so these pin
 * that both readings are accepted and that nothing in this area can reject a certificate again.
 */
class MultiLangClaimShapeTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun decode(payload: String): RegistrationCertificate =
        json.decodeFromString<RegistrationCertificateDto>(payload).toRegistrationCertificate()

    @Test
    fun `a nested srv_description is flattened rather than rejected`() {
        val certificate = decode(
            """
            {
              "sub": "NTRDE-0000",
              "srv_description": [[{"lang":"en","value":"Web Relying Party"}]]
            }
            """.trimIndent()
        )

        assertEquals(
            listOf(LocalizedText(language = "en", value = "Web Relying Party")),
            certificate.serviceDescription,
        )
    }

    @Test
    fun `a flat srv_description is read the same way`() {
        val certificate = decode(
            """
            {
              "sub": "NTRDE-0000",
              "srv_description": [{"lang":"en","content":"Web Relying Party"}]
            }
            """.trimIndent()
        )

        assertEquals(
            listOf(LocalizedText(language = "en", value = "Web Relying Party")),
            certificate.serviceDescription,
        )
    }

    @Test
    fun `both shapes in one certificate are read, as the playground sends them`() {
        val certificate = decode(
            """
            {
              "sub": "NTRDE-9BC70F9B649486D5",
              "srv_description": [[{"lang":"en","value":"Web Relying Party"}]],
              "purpose": [
                {"lang":"en-US","value":"to buy alcohol"},
                {"lang":"en-US","value":"um Alkohol zu kaufen"}
              ]
            }
            """.trimIndent()
        )

        assertEquals(listOf("Web Relying Party"), certificate.serviceDescription.map { it.value })
        assertEquals(
            listOf("to buy alcohol", "um Alkohol zu kaufen"),
            certificate.purpose.map { it.value },
        )
    }

    @Test
    fun `a multi-language claim of an unusable shape is dropped, not raised`() {
        // Whatever an issuer puts here, it must not cost the certificate. There is no shape this
        // may throw on, because nothing downstream reads these claims to decide anything.
        listOf(
            """"srv_description": "just a string"""",
            """"srv_description": {"lang":"en","value":"an object, not a list"}""",
            """"srv_description": [42, null, ["nested", "strings"]]""",
            """"srv_description": [{"value":"no lang at all"}]""",
            """"srv_description": []""",
        ).forEach { claim ->
            val certificate = decode("""{"sub":"NTRDE-0000",$claim}""")
            assertTrue(
                "expected no descriptions for $claim, got ${certificate.serviceDescription}",
                certificate.serviceDescription.isEmpty(),
            )
        }
    }

    @Test
    fun `one unusable entry does not cost the others`() {
        val certificate = decode(
            """
            {
              "sub": "NTRDE-0000",
              "purpose": [{"lang":"en","value":"kept"}, 42, {"value":"no lang"}, {"lang":"de","value":"auch"}]
            }
            """.trimIndent()
        )

        assertEquals(listOf("kept", "auch"), certificate.purpose.map { it.value })
    }

    @Test
    fun `an unusable description does not stop the claims that decide the outcome being read`() {
        // The point of the whole change: the checks that matter must still see their input.
        val certificate = decode(
            """
            {
              "sub": "NTRDE-9BC70F9B649486D5",
              "srv_description": "nonsense",
              "status": {"status_list": {"idx": 9604, "uri": "https://example.invalid/status-list"}},
              "credentials": [
                {
                  "format": "dc+sd-jwt",
                  "meta": {"vct_values": ["urn:eudi:pid:de:1"]},
                  "claim": [{"path": ["age_equal_or_over", "16"]}]
                }
              ]
            }
            """.trimIndent()
        )

        assertTrue(certificate.serviceDescription.isEmpty())
        // binding
        assertEquals(listOf("NTRDE-9BC70F9B649486D5"), certificate.identifiers.map { it.value })
        // revocation
        assertEquals("https://example.invalid/status-list", certificate.status?.uri)
        // over-asking
        assertEquals(1, certificate.requestedCredentials.size)
        assertEquals(
            listOf(ClaimPathElement.Claim("age_equal_or_over"), ClaimPathElement.Claim("16")),
            certificate.requestedCredentials.single().claims.single().path,
        )
    }
}
