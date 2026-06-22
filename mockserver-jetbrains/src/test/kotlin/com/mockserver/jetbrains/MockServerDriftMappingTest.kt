package com.mockserver.jetbrains

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Unit tests for the pure drift→file mapping behind the Phase 4 drift quick-fix. */
class MockServerDriftMappingTest {

    private val driftBody = """
        {
          "count": 3,
          "drifts": [
            { "expectationId": "exp-A", "driftType": "BREAKING", "field": "$.name", "expectedValue": "Bob", "actualValue": "Bobby", "confidence": "0.9" },
            { "expectationId": "exp-A", "driftType": "INFORMATIONAL", "field": "$.newField", "actualValue": "extra" },
            { "expectationId": "exp-Z", "driftType": "WARNING", "field": "$.x" }
          ]
        }
    """.trimIndent()

    @Test
    fun `expectationIds reads ids from a single object and an array`() {
        assertEquals(listOf("exp-A"), MockServerDriftMapping.expectationIds("""{ "id": "exp-A", "httpRequest": {} }"""))
        assertEquals(
            listOf("exp-A", "exp-B"),
            MockServerDriftMapping.expectationIds("""[ { "id": "exp-A" }, { "id": "exp-B" }, { "httpRequest": {} } ]""")
        )
        assertTrue(MockServerDriftMapping.expectationIds("not json").isEmpty())
    }

    @Test
    fun `driftForFile intersects drift records with the file's expectation ids`() {
        val file = """[ { "id": "exp-A", "httpRequest": {} }, { "id": "exp-B" } ]"""
        val result = MockServerDriftMapping.driftForFile(driftBody, file)
        // exp-A overlaps (2 records); exp-B has no drift; exp-Z is not in the file.
        assertEquals(1, result.size)
        assertEquals("exp-A", result[0].expectationId)
        assertEquals(2, result[0].entries.size)
        assertEquals("BREAKING", result[0].entries[0].driftType)
        assertEquals("$.name", result[0].entries[0].field)
    }

    @Test
    fun `driftForFile is empty when there is no overlap`() {
        assertTrue(MockServerDriftMapping.driftForFile(driftBody, """{ "id": "unrelated" }""").isEmpty())
        assertTrue(MockServerDriftMapping.driftForFile("{}", """{ "id": "exp-A" }""").isEmpty())
    }

    @Test
    fun `formatFileDrift summarises affected expectations`() {
        val file = """{ "id": "exp-A" }"""
        val report = MockServerDriftMapping.formatFileDrift(MockServerDriftMapping.driftForFile(driftBody, file))
        assertTrue(report.contains("Drift affects 1 expectation"))
        assertTrue(report.contains("Expectation exp-A"))
        assertTrue(report.contains("[BREAKING]"))
        assertTrue(report.contains("confidence 0.9"))
    }

    @Test
    fun `formatFileDrift reports cleanly when empty`() {
        assertEquals(
            "No drift affects the expectations declared in this file.",
            MockServerDriftMapping.formatFileDrift(emptyList())
        )
    }
}
