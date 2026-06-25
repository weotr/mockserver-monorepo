package com.mockserver.jetbrains

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType

/**
 * Associates the bundled MockServer expectation JSON Schema with
 * `*.mockserver.json` / `*.mockserver.jsonc` files, giving validation,
 * completion, and hover documentation from the IDE's built-in JSON support.
 *
 * The schema is the same one the server validates against, generated from
 * mockserver-core (see scripts/generate-editor-expectation-schema.mjs).
 */
class MockServerSchemaProviderFactory : JsonSchemaProviderFactory {
    override fun getProviders(project: Project): List<JsonSchemaFileProvider> =
        listOf(MockServerSchemaFileProvider())
}

class MockServerSchemaFileProvider : JsonSchemaFileProvider {

    override fun getName(): String = "MockServer expectation"

    override fun isAvailable(file: VirtualFile): Boolean =
        file.name.endsWith(".mockserver.json") || file.name.endsWith(".mockserver.jsonc")

    override fun getSchemaFile(): VirtualFile? =
        JsonSchemaProviderFactory.getResourceFile(this::class.java, SCHEMA_RESOURCE)

    override fun getSchemaType(): SchemaType = SchemaType.embeddedSchema

    companion object {
        private const val SCHEMA_RESOURCE = "/schemas/mockserver-expectation.schema.json"
    }
}
