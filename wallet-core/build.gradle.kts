import project.convention.logic.config.LibraryModule
import project.convention.logic.kover.KoverExclusionRules
import project.convention.logic.kover.excludeFromKoverReport

/*
 * Copyright (c) 2024-2025 European Commission
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

// EUDI-removed
/*
import com.android.build.gradle.api.LibraryVariant
import com.github.jk1.license.filter.ExcludeTransitiveDependenciesFilter
import com.github.jk1.license.filter.LicenseBundleNormalizer
import com.github.jk1.license.filter.ReduceDuplicateLicensesFilter
import com.github.jk1.license.render.InventoryMarkdownReportRenderer
import com.vanniktech.maven.publish.AndroidMultiVariantLibrary
import java.util.Locale
*/

plugins {
    // EUDI-added
    id("project.android.library")
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    id("kotlin-parcelize")
    // BEGIN EUDI-changed
    /*
    alias(libs.plugins.dokka)
    alias(libs.plugins.dependency.license.report)
    alias(libs.plugins.dependencycheck)
    alias(libs.plugins.sonarqube)
    alias(libs.plugins.maven.publish)
    jacoco
    */
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.dependency.license.report) apply false
    alias(libs.plugins.jetbrains.kotlin.jvm) apply false
    // END EUDI-changed
}

// EUDI-removed
/*
jacoco {
    toolVersion = libs.versions.jacoco.get()
}

val NAMESPACE: String by project
val GROUP: String by project
val POM_SCM_URL: String by project
val POM_DESCRIPTION: String by project
*/

android {
    namespace = "eu.europa.ec.eudi.wallet"
    // EUDI-removed
    /*
    namespace = NAMESPACE
    group = GROUP
    compileSdk = 34

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testApplicationId = "$NAMESPACE.test"
        testHandleProfiling = true
        testFunctionalTest = true

        consumerProguardFiles("consumer-rules.pro")

    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
    }
    kotlinOptions {
        jvmTarget = libs.versions.java.get()
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    sourceSets.getByName("test").apply {
        res.setSrcDirs(files("resources"))
    }
    */

    packaging {
        resources {
            excludes += listOf("/META-INF/{AL2.0,LGPL2.1}")
            excludes += listOf("/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
            // EUDI-added
            excludes += listOf(
                "META-INF/kotlinx-io.kotlin_module",
                "META-INF/atomicfu.kotlin_module",
                "META-INF/kotlinx-coroutines-io.kotlin_module",
                "META-INF/kotlinx-coroutines-core.kotlin_module",
            )
        }
    }

    // EUDI-removed
    /*
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    afterEvaluate {
        libraryVariants.forEach { createJacocoTasks(it) }
    }
    */
}

// EUDI-added
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
    implementation(libs.google.identity.mdoc) {
        exclude(group = "org.bouncycastle")
    }

    // EUDI-changed
    // implementation(libs.appcompat)
    implementation(libs.androidx.appcompat)
    // OpenID4VCI
    // EUID-added
    api(libs.eudi.lib.jvm.openid4vci.kt)
    implementation(libs.nimbus.oauth2.oidc.sdk)
    // Siop-Openid4VP library
    implementation(libs.eudi.lib.jvm.siop.openid4vp.kt) {
        exclude(group = "org.bouncycastle")
    }
    // SD-JWT VC library
    implementation(libs.eudi.lib.jvm.sdjwt.kt)

    // Document status
    api(libs.eudi.lib.kmp.statium)

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
    // EUDI-removed
    // runtimeOnly(libs.ktor.client.android)

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
    testImplementation(libs.robolectric)
    // EUDI-added
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

// EUDI-added
excludeFromKoverReport(
    excludedClasses = KoverExclusionRules.CoreLogic.classes,
    excludedPackages = KoverExclusionRules.CoreLogic.packages,
)
// EUDI-removed: Rest of this file