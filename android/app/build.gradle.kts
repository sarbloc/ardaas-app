plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------------------
// Shared scripture (#82)
//
// The canonical Ardaas text lives ONCE, in the iOS app's resources. Android
// packages it straight from there: no second copy in the tree and no copy in
// build/ either, so there is nothing that can drift. A correction to the
// Gurmukhi lands in both apps from one edit — and one proof-read.
// ---------------------------------------------------------------------------
val sharedContentDir: File = rootDir.parentFile.resolve("Ardaas/Resources")

// Exactly the files Android expects. Deliberately an exact set, not a glob:
// a deleted or renamed file fails the build, and so does a NEW file — anything
// added to the iOS resources is a decision about Android too, made on purpose.
val sharedContentFiles = setOf(
    "ardaas-buddha-dal.json",
    "ardaas-sgpc.json",
    "occasions.json",
    "variants.json",
)

abstract class VerifySharedContent : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val contentDir: DirectoryProperty

    @get:Input
    abstract val expectedFiles: SetProperty<String>

    @get:OutputFile
    abstract val stamp: RegularFileProperty

    @TaskAction
    fun verify() {
        val dir = contentDir.get().asFile
        val actual = dir.listFiles().orEmpty().filter { it.isFile }.map { it.name }.toSortedSet()
        val expected = expectedFiles.get().toSortedSet()
        if (actual != expected) {
            throw GradleException(
                "Shared Ardaas content in ${dir.path} does not match what Android expects.\n" +
                    "  missing:    ${expected - actual}\n" +
                    "  unexpected: ${actual - expected}\n" +
                    "Update sharedContentFiles in android/app/build.gradle.kts only once " +
                    "Android is meant to ship the change.",
            )
        }
        stamp.get().asFile.writeText(actual.joinToString("\n"))
    }
}

val verifySharedContent = tasks.register<VerifySharedContent>("verifySharedContent") {
    contentDir.set(sharedContentDir)
    expectedFiles.set(sharedContentFiles)
    stamp.set(layout.buildDirectory.file("verifySharedContent/ok.txt"))
}

tasks.named("preBuild") { dependsOn(verifySharedContent) }

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addStaticSourceDirectory(sharedContentDir.absolutePath)
    }
}

android {
    namespace = "io.sarbloc.ardaas"
    // Compose BOM 2026.09 requires compiling against 37. compileSdk only
    // gates which APIs we may call; runtime behaviour opt-ins follow
    // targetSdk, kept at 36 until those changes are reviewed.
    compileSdk = 37

    defaultConfig {
        // Same identifier as the iOS app. The stores are separate namespaces,
        // so sharing it costs nothing and keeps the two apps recognisably one.
        applicationId = "io.sarbloc.ardaas"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // Exposes the merged assets directory — exactly what gets packaged —
        // to JVM unit tests via com/android/tools/test_config.properties.
        unitTests.isIncludeAndroidResources = true
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("ardaas.sharedContentDir", sharedContentDir.absolutePath)
    // Re-run the parity test whenever the scripture changes.
    inputs.dir(sharedContentDir).withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
