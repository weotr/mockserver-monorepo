package com.mockserver.jetbrains

import com.google.gson.JsonParser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.ide.JsonSchemaService

/**
 * Headless IntelliJ Platform regression test for the JSON-schema wiring.
 *
 * The provider factory ([MockServerSchemaProviderFactory]) is registered in
 * plugin.xml under the extension point `JavaScript.JsonSchema.ProviderFactory`.
 * Registering it under the wrong EP name — or returning a null schema resource
 * from [MockServerSchemaFileProvider.getSchemaFile] — silently disables schema
 * validation/completion with NO build failure and NO log error. These tests run
 * in a real (headless) IDE so any such regression fails the build.
 */
class MockServerSchemaWiringTest : BasePlatformTestCase() {

    /** [MockServerSchemaFileProvider.getSchemaFile] must resolve a real, parseable schema. */
    fun testSchemaResourceResolvesAndIsValidJson() {
        val schemaFile = JsonSchemaProviderFactory.getResourceFile(
            MockServerSchemaFileProvider::class.java,
            "/schemas/mockserver-expectation.schema.json",
        )
        assertNotNull(
            "Schema resource /schemas/mockserver-expectation.schema.json must be on the classpath; " +
                "a null here means getSchemaFile() returns null and validation is silently disabled",
            schemaFile,
        )
        val content = String(schemaFile!!.contentsToByteArray(), Charsets.UTF_8)
        assertTrue("Schema resource must parse as JSON", JsonParser.parseString(content).isJsonObject)
    }

    /** The provider's own filename predicate must match *.mockserver.json and reject unrelated JSON. */
    fun testProviderIsAvailableMatchesFilename() {
        val provider = MockServerSchemaFileProvider()
        val mockserverFile = myFixture.configureByText("demo.mockserver.json", "{}").virtualFile
        val plainFile = myFixture.configureByText("plain.json", "{}").virtualFile

        assertTrue("provider must apply to *.mockserver.json", provider.isAvailable(mockserverFile))
        assertFalse("provider must not apply to unrelated *.json", provider.isAvailable(plainFile))
    }

    /**
     * Authoritative regression guard: the platform's [JsonSchemaService] must resolve
     * OUR schema for a *.mockserver.json file. This only succeeds if the factory is
     * registered under the correct extension point — the exact bug this test exists for.
     */
    fun testPlatformBindsOurSchemaToMockserverFile() {
        val vFile = myFixture.configureByText("demo.mockserver.json", "{}").virtualFile
        val service = JsonSchemaService.Impl.get(project)

        assertTrue(
            "JsonSchemaService must consider demo.mockserver.json schema-applicable; " +
                "a false here means the provider factory is not registered under " +
                "JavaScript.JsonSchema.ProviderFactory",
            service.isApplicableToFile(vFile),
        )

        val schemaFiles = service.getSchemaFilesForFile(vFile)
        assertTrue(
            "JsonSchemaService must resolve our mockserver-expectation.schema.json for the file; " +
                "resolved instead: ${schemaFiles.map { it.name }}",
            schemaFiles.any { it.name == "mockserver-expectation.schema.json" },
        )
    }
}
