plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
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

// ---------------------------------------------------------------------------
// Versioning (#92)
//
// versionCode is the commit count on main, passed in by the release workflow
// (-PardaasVersionCode=N), the same number the iOS build uses for
// CURRENT_PROJECT_VERSION. Local and PR builds default to 1: nothing they
// produce is ever uploaded, so they never need a real one.
//
// versionName lives in gradle.properties (ardaasVersionName) so the release
// workflow can name the artifact without evaluating Gradle. Keep it in step
// with MARKETING_VERSION in project.yml — the two apps are one release.
// ---------------------------------------------------------------------------
// Play's ceiling for versionCode.
val maxVersionCode = 2_100_000_000

val ardaasVersionCode: Int = providers.gradleProperty("ardaasVersionCode").orNull.let { raw ->
    if (raw == null) {
        1
    } else {
        raw.trim().toIntOrNull()?.takeIf { it in 1..maxVersionCode }
            ?: throw GradleException(
                "ardaasVersionCode must be an integer in 1..$maxVersionCode, got \"$raw\".",
            )
    }
}

val ardaasVersionName: String = providers.gradleProperty("ardaasVersionName").orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: throw GradleException("ardaasVersionName is missing from android/gradle.properties.")

// ---------------------------------------------------------------------------
// Release signing (#92)
//
// Play App Signing holds the app-signing key; we sign with the UPLOAD key.
// The keystore and its credentials arrive only through the environment — the
// release workflow decodes the keystore to a temp file and sets all three.
// Nothing about the key is ever committed.
//
// None set (local dev, PR CI): release builds assemble UNSIGNED, as before.
// Some but not all set: a misconfigured release job, so fail now rather than
// quietly produce an unsigned bundle that Play would reject later.
//
// The keystore is PKCS12, where the key password is the store password.
// ---------------------------------------------------------------------------
data class UploadSigning(val storeFile: File, val password: String, val keyAlias: String)

val uploadSigning: UploadSigning? = run {
    // A secret that is not configured reaches the job as an EMPTY variable,
    // so blank counts as unset.
    fun env(name: String): String? = providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }

    val keystore = env("ARDAAS_UPLOAD_KEYSTORE")
    val password = env("ARDAAS_UPLOAD_KEYSTORE_PASSWORD")
    val alias = env("ARDAAS_UPLOAD_KEY_ALIAS")
    val present = mapOf(
        "ARDAAS_UPLOAD_KEYSTORE" to (keystore != null),
        "ARDAAS_UPLOAD_KEYSTORE_PASSWORD" to (password != null),
        "ARDAAS_UPLOAD_KEY_ALIAS" to (alias != null),
    )
    when {
        present.values.none { it } -> null
        present.values.all { it } -> {
            val file = File(keystore!!)
            if (!file.isFile) {
                // The path is not secret; the password never appears in a message.
                throw GradleException("ARDAAS_UPLOAD_KEYSTORE points at $file, which is not a file.")
            }
            UploadSigning(file, password!!, alias!!)
        }
        else -> throw GradleException(
            "Release signing is half-configured. Set all of ARDAAS_UPLOAD_KEYSTORE, " +
                "ARDAAS_UPLOAD_KEYSTORE_PASSWORD and ARDAAS_UPLOAD_KEY_ALIAS, or none.\n" +
                "  missing: ${present.filterValues { !it }.keys}",
        )
    }
}

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
        versionCode = ardaasVersionCode
        versionName = ardaasVersionName

        ndk {
            // ONNX Runtime ships ~20 MB of native code per ABI; Play's per-ABI
            // splits mean each phone downloads only its own. armeabi-v7a keeps
            // 32-bit phones (old devices, Android Go) able to install and read;
            // translation there still has to pass MemoryGuard, which refuses
            // low-RAM devices. x86_64 is the emulator and Chromebooks; 32-bit x86 is
            // emulator-only and dropped.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        uploadSigning?.let { upload ->
            create("release") {
                storeFile = upload.storeFile
                storeType = "pkcs12"
                storePassword = upload.password
                keyAlias = upload.keyAlias
                keyPassword = upload.password
            }
        }
    }

    buildTypes {
        release {
            // R8 stays off for the first closed-test build: ONNX Runtime
            // reaches its Java classes from JNI and by reflection, and a
            // shrinker mistake there would only show up on a device.
            isMinifyEnabled = false
            // The default for release; stated so it cannot drift.
            isDebuggable = false
            // Unset without the upload key: the release build stays unsigned.
            signingConfig = signingConfigs.findByName("release")
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

// Room writes each schema version as JSON here; the files are committed so
// every future migration is a reviewable diff (#86).
room {
    schemaDirectory("$projectDir/schemas")
}

// The transliteration parity fixture (#84) is shared with the iOS test suite:
// both engines assert the same file, so they cannot drift apart.
val transliterationParityFixture: File =
    rootDir.parentFile.resolve("ArdaasTests/Fixtures/transliteration-parity.json")

// The translation tokenizer's parity fixture (#115) and the tokenizer it was
// generated against are shared the same way: one copy, asserted by both suites.
val tokenizerParityFixture: File =
    rootDir.parentFile.resolve("ArdaasTests/Fixtures/tokenizer_parity.json")
val tokenizerSourceFixture: File =
    rootDir.parentFile.resolve("ArdaasTests/Fixtures/tokenizer_src.json")

// The real-model translation test (#118) is opt-in: point ARDAAS_MODEL_DIR at
// a directory holding the pinned catalog files (the three .onnx graphs plus
// tokenizer_src.json and dict.TGT.json). CI has no 359 MB model, so it skips.
// Its goldens are an Android test resource, not a shared ArdaasTests fixture,
// so adding or regenerating them never triggers the macOS CI build.
val modelDirectory: Provider<String> = providers.environmentVariable("ARDAAS_MODEL_DIR").orElse("")

tasks.withType<Test>().configureEach {
    // Robolectric's Android 16 (SDK 36) runtime reaches into FileDescriptor
    // internals while setting up the app; on JDK 17+ that needs this opened,
    // or every Robolectric test fails before it starts. Tests then run on the
    // targetSdk framework rather than an older pinned one.
    jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
    systemProperty("ardaas.sharedContentDir", sharedContentDir.absolutePath)
    // Re-run the parity test whenever the scripture changes.
    inputs.dir(sharedContentDir).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("ardaas.transliterationParityFixture", transliterationParityFixture.absolutePath)
    // …and the transliteration parity test whenever its fixture does.
    inputs.file(transliterationParityFixture).withPathSensitivity(PathSensitivity.NONE)
    // …and the tokenizer parity tests whenever either tokenizer fixture does.
    systemProperty("ardaas.tokenizerParityFixture", tokenizerParityFixture.absolutePath)
    inputs.file(tokenizerParityFixture).withPathSensitivity(PathSensitivity.NONE)
    systemProperty("ardaas.tokenizerSourceFixture", tokenizerSourceFixture.absolutePath)
    inputs.file(tokenizerSourceFixture).withPathSensitivity(PathSensitivity.NONE)
    // …and the end-to-end translation test whenever the model directory it is
    // pointed at changes (its goldens are a test resource, already an input).
    inputs.property("ardaas.modelDir", modelDirectory)
    systemProperty("ardaas.modelDir", modelDirectory.get())
    // The files' contents too, so swapping a model in place re-runs the test
    // rather than reusing a pass recorded against the old artifacts.
    modelDirectory.get().takeIf { it.isNotEmpty() }?.let { path ->
        inputs.files(fileTree(path) { include("*.onnx", "*.json") })
            .withPropertyName("ardaas.modelFiles")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }
}

// Host tests run ONNX Runtime from the desktop JVM artifact. The Android AAR's
// classes.jar carries the same `ai.onnxruntime` classes but only Android .so
// files, so it is substituted out of every unit-test classpath rather than
// left to shadow (or be shadowed by) the JVM jar depending on classpath order.
configurations.matching {
    it.name.endsWith("UnitTestCompileClasspath") || it.name.endsWith("UnitTestRuntimeClasspath")
}.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module(libs.onnxruntime.android.get().module.toString()))
            .using(module(libs.onnxruntime.jvm.get().toString()))
            .because("unit tests run on the host JVM, which needs the desktop native libraries")
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.onnxruntime.android)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.onnxruntime.jvm)
}
