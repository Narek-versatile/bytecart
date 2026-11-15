plugins {
    id("com.android.application")
    kotlin("plugin.compose")
}

android {
    namespace = "dev.bytecart"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.bytecart"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["main"].assets.directories.add("../games-assets")
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

// bundle the game sources from /games into the APK as assets/games/*.bcs
val copyGames by tasks.registering(Copy::class) {
    from(rootProject.file("games")) { include("*.bcs") }
    into(rootProject.layout.projectDirectory.dir("games-assets/games"))
}
tasks.named("preBuild") { dependsOn(copyGames) }

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("com.google.zxing:core:3.5.4")
}
