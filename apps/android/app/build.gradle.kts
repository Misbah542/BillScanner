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

/**
 * A signing credential, from local.properties on a developer's machine or from the
 * environment in CI. The two use different names — dotted Gradle properties locally,
 * SHOUTING_CASE variables in a workflow — so both are named explicitly rather than
 * guessed at.
 */
fun signingOr(propertyKey: String, envKey: String): String =
    (localProperties.getProperty(propertyKey) ?: System.getenv(envKey) ?: "")

/**
 * The API a release build talks to. There is deliberately no default.
 *
 * It used to default to `https://api.snaptab.app/`, a domain nobody here owns. An APK
 * is trivially decompiled, so that address is visible to anyone who downloads one —
 * and whoever registers the domain first receives the sign-in traffic of every
 * install. A build that refuses to produce such an APK is worth more than a
 * convenient default.
 *
 * So a live release has to say where it is pointing:
 *
 *   snaptab.apiBaseUrl.release=https://api.yourdomain.com/    in local.properties
 *
 * The check is scoped to the tasks that actually build one, so `assembleDemoRelease`
 * (which reaches no network at all) and every debug build stay unaffected.
 */
val releaseBaseUrl: String = signingOr("snaptab.apiBaseUrl.release", "ANDROID_RELEASE_API_BASE_URL")

if (gradle.startParameter.taskNames.any { it.contains("liveRelease", ignoreCase = true) }) {
    check(releaseBaseUrl.startsWith("https://")) {
        """
        snaptab.apiBaseUrl.release is not set, so this release APK would have no API to
        talk to — or worse, a hardcoded address on a domain you do not control.

        Add it to apps/android/local.properties:
            snaptab.apiBaseUrl.release=https://api.yourdomain.com/

        Or set ANDROID_RELEASE_API_BASE_URL in the environment. It must be https.

        To build something installable with no server at all, build the demo instead:
            ./gradlew assembleDemoRelease
        """.trimIndent()
    }
}

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

    /**
     * Release signing, from the environment rather than from a file in the repository.
     *
     * A keystore is the one secret that cannot be rotated: lose it and you can never
     * publish an update to that app again, and leak it and someone else can publish
     * one that Android will accept as yours. So it is never committed, and neither are
     * its passwords — CI decodes it from a secret into a temporary file, and a local
     * release build reads it from local.properties, which is git-ignored.
     *
     * When nothing is configured, this stays null and the release build is simply
     * unsigned, so `assembleLiveRelease` still works for checking that R8 has not
     * broken anything. An unsigned APK cannot be installed; see docs/CI.md.
     */
    val releaseStore = (System.getenv("ANDROID_KEYSTORE_PATH")
        ?: localProperties.getProperty("snaptab.keystorePath"))
        ?.let { rootProject.file(it) }
        ?.takeIf { it.exists() }

    signingConfigs {
        create("release") {
            if (releaseStore != null) {
                storeFile = releaseStore
                storePassword = signingOr("snaptab.keystorePassword", "ANDROID_KEYSTORE_PASSWORD")
                keyAlias = signingOr("snaptab.keyAlias", "ANDROID_KEY_ALIAS")
                keyPassword = signingOr("snaptab.keyPassword", "ANDROID_KEY_PASSWORD")
                enableV1Signing = false   // minSdk 26 needs no JAR signature
                enableV2Signing = true
                enableV3Signing = true
            }
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
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            // Null rather than the debug key when no keystore is configured. Falling
            // back to the debug key would produce an installable APK signed with a
            // certificate every Android developer on earth already has the private key
            // for, which is worse than one that will not install.
            signingConfig = if (releaseStore != null) signingConfigs.getByName("release") else null

            // Cleartext HTTP is allowed in debug so a local API works; release is
            // HTTPS only, enforced by the network security config.
            buildConfigField("String", "API_BASE_URL", "\"$releaseBaseUrl\"")
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
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            // flatMapLatest, used by the ViewModels that re-query when a filter changes.
            // Deliberate, and stable in practice, so opt in once here rather than
            // annotating each call site.
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
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
    implementation(libs.compose.ui.text.google.fonts)
    implementation(libs.compose.ui.tooling.preview)
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
    // Robolectric provides the Android runtime; ApplicationProvider, which is how a
    // test gets hold of a Context, lives in androidx.test:core rather than in
    // Robolectric itself. Without it BankAlertParserTest does not compile.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
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
