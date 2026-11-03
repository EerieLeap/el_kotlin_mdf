package com.eerie_leap.mdf

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MdfCommentTest {
    @Test
    fun aPlainCommentIsItsText() {
        val comment = MdfComment.parse("Oil temperature: sump, before the cooler")

        assertEquals("Oil temperature: sump, before the cooler", comment.text)
        assertTrue(comment.properties.isEmpty())
        assertTrue(comment.elements.isEmpty())
    }

    @Test
    fun anEmptyOrBlankCommentIsEmpty() {
        assertTrue(MdfComment.parse("").isEmpty)
        assertTrue(MdfComment.parse("  \n\t").isEmpty)
        assertEquals("", MdfComment.EMPTY.text)
    }

    @Test
    fun readsTheTextOfAnMdBlock() {
        val comment = MdfComment.parse("<CNcomment><TX>Oil pressure: sump, before the filter</TX></CNcomment>")

        assertEquals("Oil pressure: sump, before the filter", comment.text)
        assertTrue(comment.properties.isEmpty())
    }

    @Test
    fun readsTheHeaderCommentsCommonProperties() {
        val comment = MdfComment.parse(
            "<HDcomment><TX>Eerie Leap sensor log</TX><common_properties>" +
                "<e name=\"device_name\">esp32s3_pm_hw_02</e><e name=\"product_id\">50177</e><e name=\"empty\"></e>" +
                "</common_properties></HDcomment>",
        )

        assertEquals("Eerie Leap sensor log", comment.text)
        assertEquals(listOf("device_name" to "esp32s3_pm_hw_02", "product_id" to "50177", "empty" to ""), comment.properties.toList())
    }

    @Test
    fun readsTheFileHistorysToolElements() {
        val comment = MdfComment.parse(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><FHcomment><TX>created</TX><tool_id>rt_daq</tool_id>" +
                "<tool_vendor>Eerie Leap</tool_vendor><tool_version>1.3.0</tool_version></FHcomment>",
        )

        assertEquals("created", comment.text)
        assertEquals(mapOf("tool_id" to "rt_daq", "tool_vendor" to "Eerie Leap", "tool_version" to "1.3.0"), comment.elements)
    }

    @Test
    fun decodesTheXmlEntities() {
        val comment = MdfComment.parse(
            "<CNcomment><TX>R&amp;D &lt;test&gt; &quot;quoted&quot; &apos;x&apos; &#233; &#x20AC; &#x1F600; &unknown; &amp;amp;</TX></CNcomment>",
        )

        assertEquals("R&D <test> \"quoted\" 'x' é € 😀 &unknown; &amp;", comment.text)
    }

    @Test
    fun decodesEntitiesInPropertyNamesAndValues() {
        val comment = MdfComment.parse("<HDcomment><common_properties><e name=\"a&amp;b\">1 &lt; 2</e></common_properties></HDcomment>")

        assertEquals(mapOf("a&b" to "1 < 2"), comment.properties)
    }

    @Test
    fun readsEntriesInTreesAsPaths() {
        val comment = MdfComment.parse(
            "<HDcomment><common_properties><e name=\"top\">1</e><tree name=\"unit\"><e name=\"id\">7</e>" +
                "<tree name=\"hw\"><e name=\"rev\">B</e></tree></tree></common_properties></HDcomment>",
        )

        assertEquals(mapOf("top" to "1", "unit/id" to "7", "unit/hw/rev" to "B"), comment.properties)
    }

    @Test
    fun takesSingleQuotedAttributesAndSelfClosingTags() {
        val comment = MdfComment.parse(
            "<HDcomment><TX>t</TX><common_properties><e name='a'>1</e><e name=\"b\"/></common_properties><x/></HDcomment>",
        )

        assertEquals(mapOf("a" to "1", "b" to ""), comment.properties)
        assertEquals(mapOf("x" to ""), comment.elements)
    }

    @Test
    fun skipsCommentsCdataAndTheDocumentType() {
        val comment = MdfComment.parse(
            "<?xml version=\"1.0\"?><!DOCTYPE x><!-- a <TX>comment</TX> --><HDcomment><TX><![CDATA[a <b> & c]]></TX></HDcomment>",
        )

        assertEquals("a <b> & c", comment.text)
    }

    @Test
    fun anAttributeValueMayHoldAGreaterThanSign() {
        val comment = MdfComment.parse("<HDcomment><common_properties><e name=\"a>b\">v</e></common_properties></HDcomment>")

        assertEquals(mapOf("a>b" to "v"), comment.properties)
    }

    @Test
    fun trimsTheSpacesAroundTheText() {
        assertEquals("text", MdfComment.parse("<CNcomment>\n  <TX>\n  text \n</TX>\n</CNcomment>").text)
    }

    @Test
    fun aTextThatStartsWithABracketButIsNoXmlIsPlainText() {
        for (text in listOf("<5 V", "< something", "<unclosed><tag>")) {
            assertEquals(text, MdfComment.parse(text).text, text)
        }
    }

    @Test
    fun anUnclosedElementStillGivesWhatCameBefore() {
        val comment = MdfComment.parse("<CNcomment><TX>text</TX><common_properties><e name=\"a\">1</e>")

        assertEquals("text", comment.text)
        assertEquals(mapOf("a" to "1"), comment.properties)
    }

    @Test
    fun skipsAByteOrderMark() {
        assertEquals("text", MdfComment.parse("\uFEFF<CNcomment><TX>text</TX></CNcomment>").text)
    }

    @Test
    fun neverFailsOnRubbish() {
        val random = Random(11)
        val alphabet = "<>/=\"'&;#x!?-[] abTXe\n"

        repeat(3000) {
            val text = CharArray(random.nextInt(0, 80)) { alphabet[random.nextInt(alphabet.length)] }.concatToString()
            MdfComment.parse(text)
        }
        // Deeply nested.
        MdfComment.parse("<a>".repeat(5000) + "</a>".repeat(5000))
    }
}
