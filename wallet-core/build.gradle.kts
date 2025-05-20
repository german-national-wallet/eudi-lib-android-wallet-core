import project.convention.logic.config.LibraryModule
import project.convention.logic.kover.KoverExclusionRules
import project.convention.logic.kover.excludeFromKoverReport

/*
 * Copyright (c) 2023 European Commission
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

plugins {
    id("project.android.library")
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    id("kotlin-parcelize")
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.dependency.license.report) apply false
    alias(libs.plugins.jetbrains.kotlin.jvm) apply false
}

android {
    namespace = "eu.europa.ec.eudi.wallet"
    packaging {
        resources {
            excludes.addAll(
                listOf(
                    "/META-INF/{AL2.0,LGPL2.1}",
                    "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                    "META-INF/kotlinx-io.kotlin_module",
                    "META-INF/atomicfu.kotlin_module",
                    "META-INF/kotlinx-coroutines-io.kotlin_module",
                    "META-INF/kotlinx-coroutines-core.kotlin_module",
                )
            )
        }
    }
}
moduleConfig {
    module = LibraryModule.Core
}



dependencies {

    // EUDI libs
    api(libs.eudi.document.manager)
    api(libs.eudi.iso18013.data.transfer)
    // Identity android library
    api(libs.google.identity.android) {
        exclude(group = "org.bouncycastle")
    }

    implementation(libs.androidx.appcompat)
    // OpenID4VCI
    api(libs.eudi.lib.jvm.openid4vci.kt)
    implementation(libs.nimbus.oauth2.oidc.sdk)
    // Siop-Openid4VP library
    api(libs.eudi.lib.jvm.siop.openid4vp.kt) {
        exclude(group = "org.bouncycastle")
    }
    // SD-JWT VC library
    implementation(libs.eudi.lib.jvm.sdjwt.kt)

    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.io.core)
    implementation(libs.kotlinx.io.bytestring)
    // CBOR
    implementation(libs.cbor)
    implementation(libs.upokecenter.cbor)
    implementation(libs.cose.java)
    // Ktor Android Engine
    implementation(libs.ktor.client.logging)
    // Bouncy Castle
    implementation(libs.bouncy.castle.prov)
    implementation(libs.bouncy.castle.pkix)

    implementation(libs.ktor.client.android)
    implementation("io.ktor:ktor-client-content-negotiation:${libs.versions.ktor}")
    implementation("io.ktor:ktor-serialization-kotlinx-json:${libs.versions.ktor}")
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.logging.jvm)
    implementation(libs.ktor.utils.jvm)
    testImplementation(kotlin("test"))
    testImplementation(libs.mockk)
    testImplementation(libs.json)
    testImplementation(libs.kotlin.coroutines.test)
    testImplementation(libs.biometric.ktx)
    testImplementation(libs.junit.jupiter)

    androidTestImplementation(libs.android.junit)
    androidTestImplementation(libs.mockito.android)
    androidTestImplementation(libs.test.core)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.rules)
    androidTestImplementation(libs.test.coreKtx)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.espresso.contrib)
    androidTestImplementation(libs.espresso.intents)
}

excludeFromKoverReport(
    excludedClasses = KoverExclusionRules.CoreLogic.classes,
    excludedPackages = KoverExclusionRules.CoreLogic.packages,
)