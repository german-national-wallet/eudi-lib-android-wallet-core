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

package eu.europa.ec.eudi.wallet.trust

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class X509CertificateChainTrustTest {

    private val chain = listOf(ecLeafSignedByIntermediateCertificate, ecIntermediateCertificate)

    @Test
    fun `chain anchored in a trust anchor is trusted`() = runTest {
        val trust = X509CertificateChainTrust(listOf(rsaTrustedRootCertificate), checkRevocation = false)

        assertTrue(trust.isTrusted(chain))
    }

    @Test
    fun `chain whose anchor is not configured is not trusted`() = runTest {
        val trust = X509CertificateChainTrust(listOf(ecLeafSignedByIntermediateCertificate), checkRevocation = false)

        assertFalse(trust.isTrusted(listOf(ecIntermediateCertificate)))
    }

    @Test
    fun `chain missing its intermediate is not trusted`() = runTest {
        val trust = X509CertificateChainTrust(listOf(rsaTrustedRootCertificate), checkRevocation = false)

        assertFalse(trust.isTrusted(listOf(ecLeafSignedByIntermediateCertificate)))
    }

    @Test
    fun `revocation checking rejects a chain whose revocation status is unavailable`() = runTest {
        val trust = X509CertificateChainTrust(listOf(rsaTrustedRootCertificate), checkRevocation = true)

        assertFalse(trust.isTrusted(chain))
    }

    @Test
    fun `empty chain is not trusted`() = runTest {
        val trust = X509CertificateChainTrust(listOf(rsaTrustedRootCertificate), checkRevocation = false)

        assertFalse(trust.isTrusted(emptyList()))
    }

    @Test
    fun `empty trust anchors are rejected`() {
        assertFailsWith<IllegalArgumentException> { X509CertificateChainTrust(emptyList(), checkRevocation = false) }
    }
}
