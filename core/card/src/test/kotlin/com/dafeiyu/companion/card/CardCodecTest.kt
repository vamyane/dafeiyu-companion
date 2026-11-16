package com.dafeiyu.companion.card

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CardCodecTest {

    private val cardJson = """
        {
          "spec": "chara_card_v2",
          "spec_version": "2.0",
          "unknown_top": {"keep": true},
          "data": {
            "name": "大肥鱼",
            "description": "一条会聊天的鱼",
            "personality": "嘴硬心软",
            "scenario": "手机里",
            "first_mes": "你来啦。",
            "mes_example": "{{user}}: 在吗\n{{char}}: 在。",
            "system_prompt": "保持简短",
            "post_history_instructions": "",
            "alternate_greetings": ["今天怎么样。"],
            "tags": ["陪伴"],
            "creator": "test",
            "character_version": "1.0",
            "extensions": {
              "x_companion": {
                "version": 1,
                "personality": {"clinginess": 0.7},
                "schedule": [{"start":"08:30","end":"18:00","activity":"WORKING"}],
                "speech": {"maxChars": 40, "maxSentences": 2, "forbidden": ["作为AI"]}
              },
              "unknown_ext": 42
            },
            "character_book": {
              "entries": [
                {"keys": ["面试"], "content": "用户明天上午十点有面试", "enabled": true}
              ]
            }
          }
        }
    """.trimIndent()

    @Test
    fun parsesV2CardAndCompanionExtension() {
        val card = CardCodec.decodeJson(cardJson)
        assertEquals("chara_card_v2", card.spec)
        assertEquals("大肥鱼", card.data.name)
        assertEquals("嘴硬心软", card.data.personality)
        assertEquals("你来啦。", card.data.firstMessage)
        assertEquals(1, card.data.alternateGreetings.size)
        assertEquals(1, card.data.worldInfo.size)
        assertEquals("面试", card.data.worldInfo.first().keys.first())
        assertEquals(0.7, card.data.companion?.clinginess)
        assertEquals(1, card.data.companion?.schedule?.size)
        assertEquals(40, card.data.companion?.speech?.maxChars)
    }

    @Test
    fun profileUsesCompanionExtension() {
        val profile = CardCodec.decodeJson(cardJson).toProfile()
        assertEquals("大肥鱼", profile.name)
        assertEquals(40, profile.maxChars)
        assertEquals(0.7, profile.defaultClinginess)
        assertEquals(1, profile.schedule.size)
    }

    @Test
    fun jsonRoundTripPreservesUnknownFields() {
        val card = CardCodec.decodeJson(cardJson)
        val encoded = CardCodec.encodeJson(card)
        assertTrue(encoded.contains("unknown_top"))
        assertTrue(encoded.contains("unknown_ext"))
        val again = CardCodec.decodeJson(encoded)
        assertEquals(card.data.name, again.data.name)
        assertEquals(card.data.companion?.clinginess, again.data.companion?.clinginess)
    }

    @Test
    fun decodesPngTextChunk() {
        val png = basePng()
        val encoded = Base64.getEncoder()
            .encodeToString(cardJson.toByteArray(StandardCharsets.UTF_8))
        val withCard = PngChunks.withText(png, "chara", encoded)

        val card = CardCodec.decode(withCard)
        assertEquals("大肥鱼", card.data.name)
        assertEquals(0.7, card.data.companion?.clinginess)
    }

    @Test
    fun exportsPngThatCanBeReadBack() {
        val card = CardCodec.decodeJson(cardJson)
        val png = CardCodec.encodePng(basePng(), card)
        val again = CardCodec.decode(png)
        assertEquals("大肥鱼", again.data.name)
        assertTrue(PngChunks.textMap(png).containsKey("chara"))
    }

    @Test
    fun rejectsCardWithoutName() {
        assertFailsWith<CardParseException> {
            CardCodec.decodeJson("""{"data":{"description":"没有名字"}}""")
        }
    }

    private fun basePng(): ByteArray {
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }
}
