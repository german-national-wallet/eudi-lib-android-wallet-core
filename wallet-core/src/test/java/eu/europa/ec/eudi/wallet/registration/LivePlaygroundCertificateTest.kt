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
 * The registration certificate the EUDI playground's relying party actually sends, captured verbatim
 * from `playground.eudi-wallet.dev/alcohol-shop` on 2026-09-22.
 *
 * It is authentic - signed by a registrar the wallet trusts - and asks for exactly the two claims it
 * registered for, yet the wallet used to refuse it as MALFORMED and tell the user the service had no
 * permission to request data. The cause was `srv_description` being one array deeper than `purpose`
 * in the same payload.
 *
 * Kept as a fixture rather than reduced to a synthetic case: it is the real shape a real issuer
 * emits, and the regression it guards against was invisible in every synthetic payload we had.
 */
class LivePlaygroundCertificateTest {

    @Test
    fun `the playground's certificate parses, and the claims the checks read survive`() {
        val certificate = Json { ignoreUnknownKeys = true }
            .decodeFromString<RegistrationCertificateDto>(PLAYGROUND_PAYLOAD)
            .toRegistrationCertificate()

        // The display claims, in both shapes.
        assertEquals(listOf("Web Relying Party"), certificate.serviceDescription.map { it.value })
        assertEquals(
            listOf("to buy alcohol", "um Alkohol zu kaufen"),
            certificate.purpose.map { it.value },
        )

        // What the binding check reads.
        assertEquals(listOf("NTRDE-9BC70F9B649486D5"), certificate.identifiers.map { it.value })
        // What the revocation check reads.
        assertEquals(
            "https://sandbox.eudi-wallet.org/status-management/status-list",
            certificate.status?.uri,
        )
        // What the report button reads.
        assertEquals(
            "https://www.bfdi.bund.de/EN/Home/home_node.html",
            certificate.supervisoryAuthority?.uri,
        )

        // What the over-asking check reads: the registered scope, claim for claim.
        assertEquals(2, certificate.requestedCredentials.size)
        val registered = certificate.requestedCredentials.map { credential ->
            credential.format to credential.claims.map { claim ->
                claim.path.joinToString("/")
            }
        }
        assertTrue(
            "registered scope was $registered",
            registered.containsAll(
                listOf(
                    "dc+sd-jwt" to listOf("age_equal_or_over/16"),
                    "mso_mdoc" to listOf("eu.europa.ec.eudi.pid.1/age_over_16"),
                ),
            ),
        )
    }

    private companion object {
        /** Verbatim payload of the `rc-wrp+jwt` the playground serves; only whitespace was added. */
        const val PLAYGROUND_PAYLOAD = """
            {
              "name": "sprind",
              "sub_ln": "sprind",
              "sub": "NTRDE-9BC70F9B649486D5",
              "country": "DE",
              "registry_uri": "https://sandbox.eudi-wallet.org",
              "srv_description": [[{"lang": "en", "value": "Web Relying Party"}]],
              "entitlements": ["https://uri.etsi.org/19475/Entitlement/Service_Provider"],
              "privacy_policy": "http://exmaple.com/privacy-policy",
              "info_uri": "https://sandbox.eudi-wallet.org",
              "support_uri": "http://example.com/support",
              "supervisory_authority": {
                "email": "poststelle@bfdi.bund.de",
                "phone": "+49 (0)228-997799-0",
                "uri": "https://www.bfdi.bund.de/EN/Home/home_node.html"
              },
              "status": {
                "status_list": {
                  "idx": 9604,
                  "uri": "https://sandbox.eudi-wallet.org/status-management/status-list"
                }
              },
              "purpose": [
                {"lang": "en-US", "value": "to buy alcohol"},
                {"lang": "en-US", "value": "um Alkohol zu kaufen"}
              ],
              "credentials": [
                {
                  "format": "dc+sd-jwt",
                  "meta": {"vct_values": ["urn:eudi:pid:de:1"]},
                  "claim": [{"path": ["age_equal_or_over", "16"]}]
                },
                {
                  "format": "mso_mdoc",
                  "meta": {"doctype_value": "eu.europa.ec.eudi.pid.1"},
                  "claim": [{"path": ["eu.europa.ec.eudi.pid.1", "age_over_16"]}]
                }
              ]
            }
        """
    }
}
