// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    // Mismo motivo que en la app de TV (ver app/build.gradle.kts): solo se
    // aplica de verdad ahí si ya existe google-services.json, para no
    // romper un checkout que todavía no lo tenga.
    id("com.google.gms.google-services") version "4.4.4" apply false
}
