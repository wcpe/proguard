package proguard.fixer.kotlin

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import proguard.classfile.ClassPool
import proguard.classfile.LibraryClass
import proguard.classfile.ProgramClass
import proguard.classfile.kotlin.KotlinClassKindMetadata
import proguard.classfile.kotlin.visitor.ReferencedKotlinMetadataVisitor
import proguard.testutils.ClassPoolBuilder
import proguard.testutils.KotlinSource

class KotlinAnnotationFlagFixerTest : FreeSpec({

    "Given annotated program Kotlin metadata references a library class" - {
        val (programClassPool, libraryClassPool) =
            createKotlinProgramClassPools(
                "AnnotatedProgram.kt",
                """
                @Target(AnnotationTarget.CLASS)
                annotation class Marker

                @Marker
                class AnnotatedProgram
                """.trimIndent(),
            )
        val metadata = programClassPool.kotlinClassMetadata("AnnotatedProgram")
        val originalHasAnnotations = metadata.flags.hasAnnotations
        metadata.referencedClass = libraryClassPool.realLibraryClass()

        "When fixing Kotlin annotation flags through the program class pool" - {
            shouldNotThrowAny {
                programClassPool.classesAccept(ReferencedKotlinMetadataVisitor(KotlinAnnotationFlagFixer()))
            }

            "Then the program metadata annotation flag is preserved" {
                originalHasAnnotations shouldBe true
                metadata.flags.hasAnnotations shouldBe originalHasAnnotations
            }
        }
    }

    "Given unannotated program Kotlin metadata references a library class" - {
        val (programClassPool, libraryClassPool) =
            createKotlinProgramClassPools(
                "PlainProgram.kt",
                """
                class PlainProgram
                """.trimIndent(),
            )
        val metadata = programClassPool.kotlinClassMetadata("PlainProgram")
        val originalHasAnnotations = metadata.flags.hasAnnotations
        metadata.referencedClass = libraryClassPool.realLibraryClass()

        "When fixing Kotlin annotation flags through the program class pool" - {
            shouldNotThrowAny {
                programClassPool.classesAccept(ReferencedKotlinMetadataVisitor(KotlinAnnotationFlagFixer()))
            }

            "Then the program metadata annotation flag is preserved" {
                originalHasAnnotations shouldBe false
                metadata.flags.hasAnnotations shouldBe originalHasAnnotations
            }
        }
    }
})

private fun createKotlinProgramClassPools(
    fileName: String,
    source: String,
) = ClassPoolBuilder.fromSource(KotlinSource(fileName, source))

private fun ClassPool.kotlinClassMetadata(className: String): KotlinClassKindMetadata =
    getClass(className)
        .shouldBeInstanceOf<ProgramClass>()
        .kotlinMetadata
        .shouldBeInstanceOf<KotlinClassKindMetadata>()

private fun ClassPool.realLibraryClass(): LibraryClass =
    getClass("java/lang/Object")
        .shouldBeInstanceOf<LibraryClass>()
