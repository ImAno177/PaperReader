package dev.paperreader.logic.provider.builtin

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.paperreader.logic.provider.ProviderException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArxivProviderAndroidTest {
    @Test
    fun secureAtomParserWorksOnAndroid() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <id>http://arxiv.org/abs/1706.03762v7</id>
                <title>Attention Is All You Need</title>
              </entry>
            </feed>
        """.trimIndent()

        val paper = ArxivProvider().parse(xml).single()

        assertEquals("1706.03762v7", paper.providerRecordId)
        assertEquals("Attention Is All You Need", paper.title)
    }

    @Test
    fun doctypeIsRejectedOnAndroid() {
        val error = runCatching {
            ArxivProvider().parse(
                """<?xml version="1.0"?><!DOCTYPE feed [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><feed xmlns="http://www.w3.org/2005/Atom">&xxe;</feed>""",
            )
        }.exceptionOrNull()

        assertTrue(error is ProviderException.InvalidResponse)
    }
}
