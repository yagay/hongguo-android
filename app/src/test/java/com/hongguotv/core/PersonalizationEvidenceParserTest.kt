package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PersonalizationEvidenceParserTest {
    @Test fun loginAndDynamicCardsDoNotProvePersonalization() {
        val data = JSONObject().put("session_id", "session").put("tab_item", JSONArray())
        assertEquals(PersonalizationEvidence.UNDECLARED,
            PersonalizationEvidenceParser.inspect(JSONObject().put("data", data)))
    }

    @Test fun acceptsOnlyExplicitResponseLevelSignals() {
        val data = JSONObject().put("personalization_enabled", true)
        assertEquals(PersonalizationEvidence.SERVER_ENABLED,
            PersonalizationEvidenceParser.inspect(JSONObject().put("data", data)))
        data.put("personalization_enabled", false)
        assertEquals(PersonalizationEvidence.SERVER_DISABLED,
            PersonalizationEvidenceParser.inspect(JSONObject().put("data", data)))
    }

    @Test fun cardLevelPersonalizationIsNotProof() {
        val cards = JSONArray().put(JSONObject().put("is_personalized", true))
        val payload = JSONObject().put("data", JSONObject().put("video_data", cards))
        assertEquals(PersonalizationEvidence.UNDECLARED,
            PersonalizationEvidenceParser.inspect(payload))
    }

    @Test fun contradictorySignalsAreUndeclared() {
        val tab = JSONObject().put("is_personalized", true)
        val response = JSONObject().put("personalization_enabled", false)
        assertEquals(PersonalizationEvidence.UNDECLARED,
            PersonalizationEvidenceParser.inspect(response, tab))
    }
}
