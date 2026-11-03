import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val releaseSigning = mapOf(
    "store" to System.getenv("PAIRSHOT_RELEASE_KEYSTORE"),
    "storePassword" to System.getenv("ANDROID_KEYSTORE_PASSWORD"),
    "alias" to System.getenv("ANDROID_KEY_ALIAS"),
    "keyPassword" to System.getenv("ANDROID_KEY_PASSWORD"),
)
val hasReleaseSigning = releaseSigning.values.all { !it.isNullOrBlank() }
check(hasReleaseSigning || releaseSigning.values.all { it.isNullOrBlank() }) {
    "Release signing requires all four keystore settings; partial configuration is not allowed"
}

android {
    namespace = "com.pairshot.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.pairshot.app"
        minSdk = 29
        targetSdk = 36
        versionCode = providers.gradleProperty("pairshotVersionCode").getOrElse(appVersion.getProperty("VERSION_CODE")).toInt().also {
            require(it in 1..2_100_000_000) { "Invalid Android versionCode" }
        }
        versionName = providers.gradleProperty("pairshotVersionName").getOrElse(appVersion.getProperty("VERSION_NAME"))
    }
    signingConfigs {
        System.getenv("PAIRSHOT_DEBUG_KEYSTORE")?.takeIf(String::isNotBlank)?.let { path ->
            getByName("debug") {
                storeFile = file(path)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        if (hasReleaseSigning) create("ciRelease") {
            storeFile = file(checkNotNull(releaseSigning["store"]))
            storePassword = releaseSigning["storePassword"]
            keyAlias = releaseSigning["alias"]
            keyPassword = releaseSigning["keyPassword"]
        }
    }
    buildTypes {
        getByName("release") {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("ciRelease")
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            providers.gradleProperty("robolectricDependencyDir").orNull?.let { directory ->
                test.systemProperty("robolectric.offline", "true")
                test.systemProperty("robolectric.dependency.dir", directory)
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
}
