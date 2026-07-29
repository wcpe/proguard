package proguard.kotlinmetadata

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import proguard.AppView
import proguard.Configuration
import proguard.classfile.ClassPool
import proguard.classfile.Clazz
import proguard.classfile.LibraryClass
import proguard.classfile.kotlin.KotlinClassKindMetadata
import proguard.classfile.kotlin.KotlinConstants
import proguard.classfile.kotlin.KotlinFileFacadeKindMetadata
import proguard.classfile.kotlin.KotlinMetadata
import proguard.classfile.kotlin.KotlinTypeMetadata
import proguard.classfile.kotlin.visitor.AllTypeVisitor
import proguard.classfile.kotlin.visitor.KotlinMetadataVisitor
import proguard.classfile.kotlin.visitor.KotlinTypeVisitor
import proguard.classfile.kotlin.visitor.ReferencedKotlinMetadataVisitor
import proguard.classfile.visitor.ClassPoolFiller
import proguard.classfile.visitor.ClassVisitor
import proguard.io.ClassReader
import proguard.io.DirectorySource
import proguard.io.FileDataEntry
import proguard.io.NameFilteredDataEntryReader
import proguard.io.StreamingDataEntry
import proguard.mark.Marker
import proguard.obfuscate.NameObfuscationReferenceFixer
import proguard.obfuscate.ObfuscationPreparation
import proguard.obfuscate.Obfuscator
import proguard.resources.file.ResourceFilePool
import proguard.resources.file.visitor.ResourceFilePoolFiller
import proguard.resources.kotlinmodule.KotlinModule
import proguard.resources.kotlinmodule.io.KotlinModuleDataEntryReader
import proguard.resources.kotlinmodule.util.KotlinModuleReferenceInitializer
import proguard.testutils.ClassPoolBuilder
import proguard.util.kotlin.asserter.KotlinMetadataVerifier
import testutils.asConfiguration
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.reflect.KType
import kotlin.reflect.typeOf

interface Metadata2310Contract {
    fun describe(): KType = typeOf<KType>()
}

class Metadata2310Carrier : Metadata2310Contract

val metadata2310Type: KType = typeOf<KType>()

fun metadata2310Facade(): KType = metadata2310Type

class Kotlin2310MetadataRegressionTest : FreeSpec({
    "Kotlin 2.3.10 metadata survives rename-only obfuscation with file facade module DefaultImpls and library types" {
        Kotlin2310MetadataRegressionTestSupport.runRegression()
    }
})

private object Kotlin2310MetadataRegressionTestSupport {
    fun runRegression() {
        KotlinVersion.CURRENT.toString() shouldBe KOTLIN_VERSION

        val classesRoot = compiledTestClassesRoot()
        classesRoot.toString().replace('\\', '/') shouldContain "build/classes/kotlin/test"

        val programClassPool = readFixtureClasses(classesRoot)
        val libraryClassPool = readLibraryClasses(programClassPool)
        val resourceFilePool = readFixtureModule(classesRoot)
        resourceFilePool.resourceFileNames().toList().shouldNotBeEmpty()

        val fileFacadeClass = programClassPool.getClass(FILE_FACADE_CLASS_NAME)
        val contractClass = programClassPool.getClass(CONTRACT_CLASS_NAME)
        val defaultImplsClass = programClassPool.getClass(DEFAULT_IMPLS_CLASS_NAME)

        fileFacadeClass shouldNotBe null
        contractClass shouldNotBe null
        defaultImplsClass shouldNotBe null

        initializeModuleReferences(resourceFilePool, programClassPool, libraryClassPool)
        assertFileFacadeModuleReference(fileFacadeClass, resourceFilePool)
        assertDefaultImplsReference(contractClass, defaultImplsClass, programClassPool)
        assertLibraryTypesRemainLibraryInput(programClassPool, libraryClassPool)
        assertNoUnresolvedKotlinTypeReferences(programClassPool)

        val configuration = renameOnlyMetadataConfiguration()
        val appView = AppView(programClassPool, libraryClassPool, resourceFilePool, proguard.io.ExtraDataEntryNameMap())

        Marker(configuration).execute(appView)
        ObfuscationPreparation(configuration).execute(appView)
        Obfuscator(configuration).execute(appView)
        NameObfuscationReferenceFixer(configuration).execute(appView)

        shouldNotThrowAny {
            KotlinMetadataVerifier(configuration).execute(appView)
        }

        assertFileFacadeModuleReference(fileFacadeClass, resourceFilePool)
        assertDefaultImplsReference(contractClass, defaultImplsClass, programClassPool)
        assertLibraryTypesRemainLibraryInput(programClassPool, libraryClassPool)
    }

    private const val KOTLIN_VERSION = "2.3.10"
    private const val FIXTURE_PACKAGE = "proguard/kotlinmetadata"
    private const val FILE_FACADE_CLASS_NAME = "$FIXTURE_PACKAGE/Kotlin2310MetadataRegressionTestKt"
    private const val CONTRACT_CLASS_NAME = "$FIXTURE_PACKAGE/Metadata2310Contract"
    private const val DEFAULT_IMPLS_CLASS_NAME = "$CONTRACT_CLASS_NAME\$DefaultImpls"
    private const val CARRIER_CLASS_NAME = "$FIXTURE_PACKAGE/Metadata2310Carrier"

    private val fixtureClassNames =
        listOf(
            FILE_FACADE_CLASS_NAME,
            CONTRACT_CLASS_NAME,
            DEFAULT_IMPLS_CLASS_NAME,
            CARRIER_CLASS_NAME,
        )

    private val expectedLibraryTypes = setOf("kotlin/reflect/KType")

    private fun compiledTestClassesRoot(): Path =
        Paths.get(Kotlin2310MetadataRegressionTest::class.java.protectionDomain.codeSource.location.toURI())

    private fun readFixtureClasses(classesRoot: Path): ClassPool {
        val programClassPool = ClassPool()
        val classReader = ClassReader(false, false, false, false, true, null, ClassPoolFiller(programClassPool))

        fixtureClassNames.forEach { className ->
            val classFile = classesRoot.resolve("$className.class").toFile()
            classFile.isFile shouldBe true
            classReader.read(FileDataEntry(classFile))
        }

        return programClassPool
    }

    private fun readLibraryClasses(programClassPool: ClassPool): ClassPool =
        ClassPoolBuilder.libraryClassPool.also { libraryClassPool ->
            readMissingLibraryClasses(libraryClassPool)
            stripLibraryKotlinMetadata(libraryClassPool)
            ClassPoolBuilder.initialize(programClassPool, libraryClassPool, containsKotlinCode = true)
        }

    private fun readMissingLibraryClasses(libraryClassPool: ClassPool) {
        val classReader = ClassReader(true, false, false, false, false, null, ClassPoolFiller(libraryClassPool))

        expectedLibraryTypes.filter { libraryClassPool.getClass(it) == null }.forEach { className ->
            val resourceName = "$className.class"
            val inputStream = Metadata2310Contract::class.java.classLoader.getResourceAsStream(resourceName)
            inputStream shouldNotBe null
            classReader.read(StreamingDataEntry(resourceName, inputStream))
        }
    }

    private fun stripLibraryKotlinMetadata(libraryClassPool: ClassPool) {
        libraryClassPool.classesAccept(
            object : ClassVisitor {
                override fun visitAnyClass(clazz: Clazz) = Unit

                override fun visitLibraryClass(libraryClass: LibraryClass) {
                    libraryClass.kotlinMetadata = null
                }
            },
        )
    }

    private fun readFixtureModule(classesRoot: Path): ResourceFilePool {
        val resourceFilePool = ResourceFilePool()
        val resourceFilePoolFiller = ResourceFilePoolFiller(resourceFilePool)

        DirectorySource(classesRoot.toFile()).pumpDataEntries(
            NameFilteredDataEntryReader(
                KotlinConstants.MODULE.FILE_EXPRESSION,
                KotlinModuleDataEntryReader(resourceFilePoolFiller),
            ),
        )

        val kotlinModule =
            resourceFilePool.resourceFileNames()
                .mapNotNull { resourceFilePool.getResourceFile(it) as? KotlinModule }
                .single { module -> module.modulePackages.any { it.fqName == FIXTURE_PACKAGE } }

        kotlinModule.modulePackages.removeAll { it.fqName != FIXTURE_PACKAGE }
        val kotlinModulePackage = kotlinModule.modulePackages.single()
        kotlinModulePackage.fileFacadeNames.removeAll { it != FILE_FACADE_CLASS_NAME }
        kotlinModulePackage.multiFileClassParts.clear()

        return ResourceFilePool(kotlinModule)
    }

    private fun initializeModuleReferences(
        resourceFilePool: ResourceFilePool,
        programClassPool: ClassPool,
        libraryClassPool: ClassPool,
    ) {
        resourceFilePool.resourceFilesAccept(KotlinModuleReferenceInitializer(programClassPool, libraryClassPool))
    }

    private fun renameOnlyMetadataConfiguration(): Configuration =
        """
        -keep class kotlin.Metadata
        -keepattributes RuntimeVisibleAnnotations,InnerClasses,EnclosingMethod,Signature
        """.trimIndent().asConfiguration().also { configuration ->
            configuration.keepKotlinMetadata = true
            configuration.enableKotlinAsserter = true
        }

    private fun assertFileFacadeModuleReference(
        fileFacadeClass: Clazz,
        resourceFilePool: ResourceFilePool,
    ) {
        val kotlinModule =
            resourceFilePool.resourceFileNames()
                .mapNotNull { resourceFilePool.getResourceFile(it) as? KotlinModule }
                .single()

        var fileFacadeVisited = false
        fileFacadeClass.kotlinMetadataAccept(
            object : KotlinMetadataVisitor {
                override fun visitAnyKotlinMetadata(
                    clazz: Clazz,
                    kotlinMetadata: KotlinMetadata,
                ) = Unit

                override fun visitKotlinFileFacadeMetadata(
                    clazz: Clazz,
                    kotlinFileFacadeKindMetadata: KotlinFileFacadeKindMetadata,
                ) {
                    fileFacadeVisited = true
                    kotlinFileFacadeKindMetadata.ownerReferencedClass shouldBe fileFacadeClass
                    kotlinFileFacadeKindMetadata.referencedModule shouldBe kotlinModule
                }
            },
        )

        fileFacadeVisited shouldBe true
        kotlinModule.modulePackages.flatMap { it.fileFacadeNames } shouldContain fileFacadeClass.getName()
        kotlinModule.modulePackages.flatMap { it.referencedFileFacades }
            .any { it.ownerReferencedClass == fileFacadeClass } shouldBe true
    }

    private fun assertDefaultImplsReference(
        contractClass: Clazz,
        defaultImplsClass: Clazz,
        programClassPool: ClassPool,
    ) {
        var contractVisited = false
        contractClass.kotlinMetadataAccept(
            object : KotlinMetadataVisitor {
                override fun visitAnyKotlinMetadata(
                    clazz: Clazz,
                    kotlinMetadata: KotlinMetadata,
                ) = Unit

                override fun visitKotlinClassMetadata(
                    clazz: Clazz,
                    kotlinClassKindMetadata: KotlinClassKindMetadata,
                ) {
                    contractVisited = true
                    kotlinClassKindMetadata.referencedDefaultImplsClass shouldBe defaultImplsClass
                }
            },
        )

        contractVisited shouldBe true
        programClassPool.contains(defaultImplsClass) shouldBe true
    }

    private fun assertNoUnresolvedKotlinTypeReferences(programClassPool: ClassPool) {
        val unresolvedTypes = mutableListOf<String>()

        programClassPool.classesAccept(
            ReferencedKotlinMetadataVisitor(
                AllTypeVisitor(
                    object : KotlinTypeVisitor {
                        override fun visitAnyType(
                            clazz: Clazz,
                            kotlinTypeMetadata: KotlinTypeMetadata,
                        ) {
                            val className = kotlinTypeMetadata.className ?: return
                            if (kotlinTypeMetadata.referencedClass == null) {
                                unresolvedTypes += "${clazz.getName()}:$className"
                            }
                        }
                    },
                ),
            ),
        )

        unresolvedTypes shouldBe emptyList()
    }

    private fun assertLibraryTypesRemainLibraryInput(
        programClassPool: ClassPool,
        libraryClassPool: ClassPool,
    ) {
        val seenLibraryTypes = mutableSetOf<String>()

        programClassPool.classesAccept(
            ReferencedKotlinMetadataVisitor(
                AllTypeVisitor(
                    object : KotlinTypeVisitor {
                        override fun visitAnyType(
                            clazz: Clazz,
                            kotlinTypeMetadata: KotlinTypeMetadata,
                        ) {
                            val className = kotlinTypeMetadata.className ?: return
                            if (className in expectedLibraryTypes) {
                                seenLibraryTypes += className
                                kotlinTypeMetadata.referencedClass shouldNotBe null
                                libraryClassPool.getClass(className) shouldNotBe null
                                programClassPool.getClass(className) shouldBe null
                            }
                        }
                    },
                ),
            ),
        )

        seenLibraryTypes shouldContainAll expectedLibraryTypes
    }
}
