/*
 * Copyright (c) 2026 European Commission
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by the European
 * Commission - subsequent versions of the EUPL (the "Licence"); You may not use this work
 * except in compliance with the Licence.
 *
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the Licence is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF
 * ANY KIND, either express or implied. See the Licence for the specific language
 * governing permissions and limitations under the Licence.
 */

package eu.europa.ec.eudi.wallet.presentation

/**
 * Starts a reverse-engagement (DC API over ISO 18013-5) session against a verifier whose
 * ReaderEngagement the wallet has just scanned.
 *
 * Concrete implementation lives in the `proximity-feature` module, which depends on
 * `core/wallet-core` and therefore wires itself in via `EudiWalletConfig`. This split
 * keeps the BLE + session-encryption machinery out of `core/wallet-core` (so we don't
 * pull a Multipaz API surface into the canonical wallet API) while still exposing the
 * `startReverseEngagement` entry point through `PresentationManager` for callers that
 * want the same surface they use for forward engagement and OID4VP-over-HTTPS.
 */
interface ReverseEngagementStarter {

    /**
     * Begin a reverse-engagement session against the verifier whose ReaderEngagement is
     * carried in [readerEngagementCbor]. Returns immediately; the actual BLE transport
     * and session-encryption work is launched on the implementation's own coroutine
     * scope. Progress and outcome events are observable on the concrete implementation
     * (e.g. via the `ReverseEngagementManager.events` flow in `proximity-feature`).
     *
     * @param readerEngagementCbor the raw CBOR bytes of the verifier's ReaderEngagement
     *   as recovered from the scanned QR code
     * @throws IllegalStateException if a session is already in flight
     * @throws IllegalArgumentException if the engagement bytes cannot be parsed
     */
    fun start(readerEngagementCbor: ByteArray)
}
