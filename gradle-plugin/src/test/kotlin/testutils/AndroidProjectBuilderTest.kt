/*
 * ProGuard -- shrinking, optimization, obfuscation, and preverification
 *             of Java bytecode.
 *
 * Copyright (c) 2026 Guardsquare NV
 */

package testutils

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files.createTempDirectory

class AndroidProjectBuilderTest : FreeSpec({
    "AndroidProject writes an escaped Android SDK path" {
        val sdkDir = createTempDirectory("android sdk").toFile()
        val project = AndroidProject(environment = mapOf("ANDROID_SDK_ROOT" to sdkDir.path))

        try {
            project.create()

            File(project.rootDir, "local.properties").readText() shouldBe
                "sdk.dir=${escapeSdkDirectory(sdkDir)}\n"
            escapeSdkDirectory(File("C:\\Users\\example\\Android SDK")) shouldBe
                "C\\:\\\\Users\\\\example\\\\Android SDK"
        } finally {
            project.close()
            sdkDir.deleteRecursively()
        }
    }

    "AndroidProject does not create local.properties without a valid Android SDK" {
        val project = AndroidProject(environment = mapOf("ANDROID_SDK_ROOT" to "missing-sdk"))

        try {
            project.create()

            File(project.rootDir, "local.properties").exists() shouldBe false
        } finally {
            project.close()
        }
    }
})
