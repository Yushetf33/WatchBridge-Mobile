import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.tunombre.tvbridge.mobile"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tunombre.tvbridge.mobile"
        minSdk = 24
        targetSdk = 36
        versionCode = 25
        versionName = "1.0.24"

        // Mismo patrón que la app de TV: se lee de local.properties (no
        // versionado).
        buildConfigField(
            "String",
            "TMDB_API_KEY",
            "\"${localProperties.getProperty("TMDB_API_KEY", "")}\""
        )
        buildConfigField(
            "String",
            "LICENSE_API_URL",
            "\"${localProperties.getProperty("LICENSE_API_URL", "")}\""
        )
        buildConfigField(
            "String",
            "MONTHLY_PAYMENT_URL",
            "\"${localProperties.getProperty("MONTHLY_PAYMENT_URL", "")}\""
        )
        buildConfigField(
            "String",
            "LIFETIME_PAYMENT_URL",
            "\"${localProperties.getProperty("LIFETIME_PAYMENT_URL", "")}\""
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    signingConfigs {
        create("release") {
            val storeFilePath = localProperties.getProperty("RELEASE_STORE_FILE")
            if (storeFilePath != null) {
                storeFile = rootProject.file(storeFilePath)
                storePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Mismo mecanismo que la app de TV: R8 en modo shrink+ofuscar,
            // para dificultar clonar/decompilar la app tal cual.
            optimization {
                enable = true
            }
            // Solo firma con la config de release si local.properties trae
            // la keystore configurada; si no, deja el build sin firmar en
            // vez de fallar (para que `assembleDebug` siga funcionando en
            // checkouts nuevos sin keystore).
            if (localProperties.getProperty("RELEASE_STORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.7.1")

    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    debugImplementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.navigation:navigation-compose:2.9.6")
    implementation("androidx.browser:browser:1.9.0")
    implementation("androidx.core:core-splashscreen:1.0.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Carga de pósters en las tarjetas de Compose (la app de TV usa su
    // propio PosterLoader con bitmaps a mano para Leanback; aquí no hace
    // falta reinventarlo, Coil ya se integra directo con Compose).
    implementation("io.coil-kt:coil-compose:2.7.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
