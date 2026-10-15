package io.sarbloc.ardaas.content

import java.io.File
import java.io.InputStream
import java.util.Properties

/**
 * The merged assets directory — exactly what AGP packages into the APK —
 * exposed to JVM unit tests through `unitTests.isIncludeAndroidResources`.
 * Tests load content from here, not from the iOS source directory, so they
 * exercise what Android actually ships.
 */
object BundledAssets {
    val dir: File by lazy {
        val stream = requireNotNull(
            BundledAssets::class.java.classLoader?.getResourceAsStream("com/android/tools/test_config.properties"),
        ) { "test_config.properties missing — is unitTests.isIncludeAndroidResources enabled?" }
        val props = Properties().apply { stream.use(::load) }
        File(requireNotNull(props.getProperty("android_merged_assets")) { "android_merged_assets not in test config" })
    }

    fun open(name: String): InputStream? = dir.resolve(name).takeIf { it.isFile }?.inputStream()

    fun library(): ArdaasLibrary = ArdaasLibrary.load(::open)
}
