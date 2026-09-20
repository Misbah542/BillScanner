import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * The API base URL and the Google sign-in client id are per-developer, so they come
 * from local.properties (git-ignored) with a working default, rather than being
 * hardcoded the way the old NetworkModule had `https://your-api-base-url.com/api/`
 * baked in.
 */
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun localOr(key: String, fallback: String): String =
    (localProperties.getProperty(key) ?: System.getenv(key) ?: fallback)

android {
    namespace = "com.snaptab.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.snaptab.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // 10.0.2.2 is the host machine as seen from the Android emulator.
        buildConfigField("String", "API_BASE_URL", "\"${localOr("snaptab.apiBaseUrl", "http://10.0.2.2:4000/")}\"")
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"${localOr("snaptab.googleClientId", "")}\"")
    }

    /**
     * Two flavours, one dimension:
     *
     *   demo — no network at all. A fake implementation of SnapTabApi returns a
     *          realistic account (a split restaurant bill, a solo Swiggy debit, a flat
     *          share group, an unmatched card alert) so the whole app can be driven
     *          before the server exists. Everything else is real: the repositories, the
     *          Room cache, the split arithmetic, the error paths.
     *
     *   live — talks to the API.
     *
     * Build the demo app with:  ./gradlew installDemoDebug
     */
    flavorDimensions += "backend"
    productFlavors {
        create("demo") {
            dimension = "backend"
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
            resValue("string", "app_name", "SnapTab Demo")
            buildConfigField("Boolean", "DEMO_MODE", "true")
        }
        create("live") {
            dimension = "backend"
            buildConfigField("Boolean", "DEMO_MODE", "false")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            // Cleartext HTTP is allowed in debug so a local API works; release is
            // HTTPS only, enforced by the network security config.
            buildConfigField("String", "API_BASE_URL", "\"${localOr("snaptab.apiBaseUrl.release", "https://api.snaptab.app/")}\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // java.time on API 26, which minSdk allows but which needs desugaring.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "11"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "/META-INF/LICENSE*"
        )
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        disable += setOf("GradleDependency", "ObsoleteLintCustomCheck")
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.ui.text.google.fonts)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.coil.compose)
    implementation(libs.accompanist.permissions)

    // Push notifications. The google-services plugin is applied below only when a
    // google-services.json is present, so the build works without a Firebase
    // project — SMS-triggered notifications are posted locally and need no FCM.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.room.testing)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.mockk.android)
    debugImplementation(libs.compose.ui.test.manifest)
}

// Optional Firebase wiring: drop app/google-services.json in and server-sent
// pushes start working. Without it the app still builds and still notifies from
// the on-device SMS receiver.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
    logger.lifecycle("SnapTab: google-services.json found, FCM enabled.")
} else {
    logger.lifecycle("SnapTab: no google-services.json, FCM disabled (local notifications still work).")
}
