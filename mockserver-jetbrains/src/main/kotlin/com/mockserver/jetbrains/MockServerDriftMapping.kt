package com.mockserver.jetbrains

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Pure, no-IO, no-IDE drift→expectation-file mapping for the Phase 4 drift quick-fix.
 *
 * The `GET /mockserver/drift` body is `{ count, drifts: [ { expectationId, driftType,
 * field, expectedValue?, actualValue?, confidence } ] }`. To turn that flat list into
 * a per-file, per-expectation quick-fix, we group the drift records by `expectationId`
 * and intersect them with the expectation `id`s declared in the open `*.mockserver.json`
 * file — so the user sees only the drift that affects *their* stubs, mapped to the
 * exact expectation node, exactly as the roadmap describes.
 *
 * Both shapes are parsed defensively: a missing/malformed body yields empty results.
 */
object MockServerDriftMapping {

    /** One drift record relevant to a specific expectation in the open file. */
    data class DriftEntry(
        val expectationId: String,
        val driftType: String?,
        val field: String?,
        val expectedValue: String?,
        val actualValue: String?,
        val confidence: String?,
    )

    /** All drift affecting one expectation `id` declared in the open file. */
    data class FileDrift(val expectationId: String, val entries: List<DriftEntry>)

    /**
     * The expectation `id`s declared in [fileText] (a single expectation object or an
     * array of them). Expectations without a string `id` are skipped — drift is keyed
     * by id, so an id-less expectation cannot be mapped. Returns an empty list when the
     * text is not a JSON object/array.
     */
    fun expectationIds(fileText: String): List<String> {
        val parsed = tryParseJson(fileText) ?: return emptyList()
        val expectations: List<JsonElement> = when {
            parsed.isJsonArray -> parsed.asJsonArray.toList()
            parsed.isJsonObject -> listOf(parsed)
            else -> emptyList()
        }
        val ids = ArrayList<String>()
        for (element in expectations) {
            if (!element.isJsonObject) continue
            val obj = element.asJsonObject
            if (obj.has("id") && obj.get("id").isJsonPrimitive) {
                val id = obj.get("id").asString
                if (id.isNotBlank()) ids.add(id)
            }
        }
        return ids
    }

    /**
     * Intersect the drift records in [driftBody] with the expectation `id`s in
     * [fileText], returning one [FileDrift] per affected expectation (in the file's
     * declaration order, drift entries in body order). Empty when there is no overlap.
     */
    fun driftForFile(driftBody: String, fileText: String): List<FileDrift> {
        val byExpectation = driftRecordsByExpectation(driftBody)
        val result = ArrayList<FileDrift>()
        for (id in expectationIds(fileText)) {
            val entries = byExpectation[id] ?: continue
            if (entries.isNotEmpty()) result.add(FileDrift(id, entries))
        }
        return result
    }

    /**
     * Render a [FileDrift] list into a readable report for the quick-fix output tab.
     * Leads with a one-line summary, then one section per affected expectation.
     */
    fun formatFileDrift(fileDrifts: List<FileDrift>): String {
        if (fileDrifts.isEmpty()) {
            return "No drift affects the expectations declared in this file."
        }
        val totalEntries = fileDrifts.sumOf { it.entries.size }
        val builder = StringBuilder()
        builder.append("Drift affects ").append(fileDrifts.size)
            .append(if (fileDrifts.size == 1) " expectation" else " expectations")
            .append(" in this file (").append(totalEntries)
            .append(if (totalEntries == 1) " record).\n" else " records).\n")
        for (fileDrift in fileDrifts) {
            builder.append("\nExpectation ").append(fileDrift.expectationId).append(":\n")
            for (entry in fileDrift.entries) {
                builder.append("  [").append(entry.driftType ?: "—").append("] ")
                    .append(entry.field ?: "—").append(": expected ")
                    .append(entry.expectedValue ?: "—").append(" / actual ")
                    .append(entry.actualValue ?: "—")
                if (entry.confidence != null) builder.append(" (confidence ").append(entry.confidence).append(')')
                builder.append('\n')
            }
        }
        return builder.toString().trimEnd('\n')
    }

    // ---- internals -----------------------------------------------------

    private fun driftRecordsByExpectation(driftBody: String): Map<String, List<DriftEntry>> {
        val parsed = tryParseJson(driftBody) ?: return emptyMap()
        if (!parsed.isJsonObject) return emptyMap()
        val drifts = parsed.asJsonObject.let {
            if (it.has("drifts") && it.get("drifts").isJsonArray) it.getAsJsonArray("drifts") else JsonArray()
        }
        val grouped = LinkedHashMap<String, MutableList<DriftEntry>>()
        for (element in drifts) {
            if (!element.isJsonObject) continue
            val drift = element.asJsonObject
            val expectationId = stringField(drift, "expectationId") ?: continue
            grouped.getOrPut(expectationId) { ArrayList() }.add(
                DriftEntry(
                    expectationId = expectationId,
                    driftType = stringField(drift, "driftType"),
                    field = stringField(drift, "field"),
                    expectedValue = stringField(drift, "expectedValue"),
                    actualValue = stringField(drift, "actualValue"),
                    confidence = stringField(drift, "confidence"),
                )
            )
        }
        return grouped
    }

    private fun stringField(obj: JsonObject, field: String): String? {
        if (!obj.has(field) || obj.get(field).isJsonNull) return null
        val element = obj.get(field)
        return if (element.isJsonPrimitive) element.asString else element.toString()
    }

    private fun tryParseJson(text: String): JsonElement? =
        try {
            val element = JsonParser.parseString(text)
            if (element.isJsonObject || element.isJsonArray) element else null
        } catch (_: Exception) {
            null
        }
}
