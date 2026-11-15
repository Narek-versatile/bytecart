plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
}
java { targetCompatibility = JavaVersion.VERSION_1_8; sourceCompatibility = JavaVersion.VERSION_1_8 }

dependencies {
    implementation(project(":core"))
    implementation("com.google.zxing:core:3.5.4")
}

application {
    mainClass.set("bytecart.cli.MainKt")
    applicationName = "bytecart"
}

tasks.register<Jar>("fatJar") {
    archiveFileName.set("bytecart-cli.jar")
    manifest { attributes["Main-Class"] = "bytecart.cli.MainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
}
