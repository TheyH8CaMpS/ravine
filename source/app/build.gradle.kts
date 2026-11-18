import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keys = Properties().apply {
    val f = rootProject.file("keys.properties")
    // Read as UTF-8 so values like non-English city names (é, ø, ü) survive.
    if (f.exists()) f.reader(Charsets.UTF_8).use { load(it) }
}
// Veil's shared Trakt app: the default when keys.properties has no Trakt client ID.
val sharedTrakt = Properties().apply {
    val f = rootProject.file("trakt.properties")
    if (f.exists()) f.reader(Charsets.UTF_8).use { load(it) }
}
val ownTrakt = keys.getProperty("TRAKT_CLIENT_ID", "").isNotBlank()
if (!ownTrakt) listOf("TRAKT_CLIENT_ID", "TRAKT_REDIRECT_URI").forEach { k ->
    sharedTrakt.getProperty(k)?.let { keys.setProperty(k, it) }
}
// Escape non-ASCII as \uXXXX so the generated Java source is encoding-proof.
fun key(name: String) = "\"" + keys.getProperty(name, "").trim()
    .map { c -> if (c.code > 127) "\\u%04x".format(c.code) else c.toString() }.joinToString("") + "\""

android {
    namespace = "dev.veil.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.ravine.launcher"
        minSdk = 28 // Android 9+. Newer-only features are guarded with version checks.
        targetSdk = 34
        versionCode = 100
        versionName = "0.1.0"
        buildConfigField("String", "TRAKT_CLIENT_ID", key("TRAKT_CLIENT_ID"))
        buildConfigField("String", "TRAKT_CLIENT_SECRET", key("TRAKT_CLIENT_SECRET"))
        buildConfigField("String", "TRAKT_REDIRECT_URI", key("TRAKT_REDIRECT_URI"))
        buildConfigField("String", "TMDB_KEY", key("TMDB_KEY"))
        buildConfigField("String", "SIMKL_CLIENT_ID", key("SIMKL_CLIENT_ID")) // Ravine: watch progress via Simkl (M2)
        buildConfigField("String", "NEXTDNS_PROFILE", key("NEXTDNS_PROFILE"))
        buildConfigField("String", "NEXTDNS_API_KEY", key("NEXTDNS_API_KEY"))
        buildConfigField("String", "WEATHER_CITY", key("WEATHER_CITY"))
        // Lighter graphics for slow TVs (see AGENT_INSTALL.md). Off unless keys.properties has LITE_MODE=true.
        buildConfigField("boolean", "LITE_MODE", (keys.getProperty("LITE_MODE", "false").trim() == "true").toString())
    }

    // Ravine: a stable release key so each new APK installs over the last one on the Shield.
    // CI provides it from GitHub secrets (RAVINE_KEYSTORE_*). Without it, builds fall back to the debug key,
    // which differs per machine, so such an APK can't update an install made from another machine.
    val releaseStore = System.getenv("RAVINE_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseStore != null) create("ravine") {
            storeFile = releaseStore
            storePassword = System.getenv("RAVINE_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("RAVINE_KEY_ALIAS") ?: "ravine"
            keyPassword = System.getenv("RAVINE_KEY_PASSWORD") ?: System.getenv("RAVINE_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("ravine") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.zxing:core:3.5.3") // QR code on the Connect Trakt screen (made on the TV)
    testImplementation("junit:junit:4.13.2")
}
