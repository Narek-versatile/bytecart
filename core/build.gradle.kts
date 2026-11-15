plugins {
    kotlin("multiplatform")
}

kotlin {
    jvmToolchain(21)
    jvm {
        // Java 8 bytecode so the desktop compiler runs on old JREs too
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
    js {
        browser()
        binaries.library()
        generateTypeScriptDefinitions()
    }
    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
