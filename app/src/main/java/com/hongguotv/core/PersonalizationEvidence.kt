package com.hongguotv.core

import org.json.JSONObject

/**
 * Only an explicit recommendation RESPONSE flag is evidence of server-side
 * personalization. Login cookies, dynamic cards, different lists, and session IDs
 * are not proof that the ranking algorithm used a person's preferences.
 */
enum class PersonalizationEvidence {
    UNDECLARED,
    SERVER_ENABLED,
    SERVER_DISABLED,
}

internal object PersonalizationEvidenceParser {
    // Inspect response-level metadata only. A personalized creative/card must
    // not be mistaken for personalization of the recommendation feed.
    private val flags = listOf(
        "is_personalized",
        "is_personal_recommend",
        "personalization_enabled",
        "is_personalization_enabled",
    )

    fun inspect(response: JSONObject, vararg scopes: JSONObject?): PersonalizationEvidence {
        val nodes = mutableListOf<JSONObject>()
        scopes.filterNotNullTo(nodes)
        response.optJSONObject("data")?.let(nodes::add)
        nodes.add(response)
        var observed: Boolean? = null
        for (node in nodes) {
            for (key in flags) {
                if (!node.has(key) || node.isNull(key)) continue
                val value = when (val raw = node.opt(key)) {
                    is Boolean -> raw
                    is Number -> when (raw.toString()) {
                        "1" -> true
                        "0" -> false
                        else -> null
                    }
                    is String -> when (raw.trim().lowercase()) {
                        "true", "1" -> true
                        "false", "0" -> false
                        else -> null
                    }
                    else -> null
                } ?: continue
                // Contradictory server flags are not grounds for confirmation.
                if (observed != null && observed != value) return PersonalizationEvidence.UNDECLARED
                observed = value
            }
        }
        return when (observed) {
            true -> PersonalizationEvidence.SERVER_ENABLED
            false -> PersonalizationEvidence.SERVER_DISABLED
            null -> PersonalizationEvidence.UNDECLARED
        }
    }
}
