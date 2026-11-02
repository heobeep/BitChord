package com.music.bitchord.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class SongCreditsIpiTest {
    @Test
    fun excludesIpiIdentifiersButKeepsNumericArtistNames() {
        val payload = """
            {
              "credits": [
                {
                  "role": "composer",
                  "name": "Cedric Benjamin Leutwyler",
                  "ipi": "00861590127"
                },
                {
                  "role": "songwriter",
                  "name": "50 Cent",
                  "ipi": "00031312147"
                }
              ]
            }
        """.trimIndent()

        val credits = SongCreditsParser.fromCreditsFm(Json.parseToJsonElement(payload))

        assertEquals(
            listOf("Cedric Benjamin Leutwyler", "50 Cent"),
            credits.map { it.name },
        )
    }
}
