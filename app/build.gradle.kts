import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

fun apiOrigin(value: String, allowHttp: Boolean): String {
    val uri = URI(value)
    require(
        (uri.scheme == "https" || allowHttp && uri.scheme == "http") &&
            uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null &&
            uri.path in listOf("", "/") && (uri.port == -1 || uri.port in 1..65535)
    ) { "Bookio API URL must be an origin using an allowed scheme and, if specified, a port in 1..65535" }
    return "\"${uri.toASCIIString().trimEnd('/')}\""
}

val stagingOrigin = providers.gradleProperty("bookioStagingApiUrl").orNull

android {
    namespace = "ru.bookio.bookio"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "ru.bookio.bookio"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            val origin = providers.gradleProperty("bookioDebugApiUrl").orNull ?: "http://127.0.0.1:8080"
            buildConfigField("String", "API_ORIGIN", apiOrigin(origin, allowHttp = true))
        }
        release {
            buildConfigField("String", "API_ORIGIN", apiOrigin("https://bookio.duckdns.org", allowHttp = false))
            optimization {
                enable = false
            }
        }
        if (stagingOrigin != null) {
            create("staging") {
                initWith(getByName("release"))
                matchingFallbacks += listOf("release")
                buildConfigField("String", "API_ORIGIN", apiOrigin(stagingOrigin, allowHttp = false))
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.kotlinx.datetime)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.ktor.client.mock)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
