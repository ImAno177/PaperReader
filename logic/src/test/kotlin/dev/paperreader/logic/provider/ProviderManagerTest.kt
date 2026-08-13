package dev.paperreader.logic.provider

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderManagerTest {
    @Test
    fun `publishes built-in plugin and trust lifecycle state`() {
        val manager = MutableProviderManager(listOf(provider("arxiv")))
        manager.register(provider("community"), ProviderOrigin.COMMUNITY_PLUGIN, "org.example.provider", 2)
        manager.updateAvailable(
            listOf(AvailableProviderPlugin("org.example.new", "New source", 4, setOf("new"))),
        )
        manager.updateUntrusted(
            listOf(UntrustedProviderPlugin("org.example.bad", "ab".repeat(32), "Signer mismatch")),
        )

        assertEquals(setOf("arxiv", "community"), manager.state.value.installed.map { it.descriptor.id }.toSet())
        assertEquals("org.example.new", manager.state.value.available.single().packageName)
        assertEquals("Signer mismatch", manager.state.value.untrusted.single().reason)

        manager.unregister("community", "org.example.provider")
        assertNull(manager.get("community"))
    }

    @Test
    fun `missing saved provider resolves to explicit stub`() = runTest {
        val manager = MutableProviderManager(emptyList())
        val stub = manager.getOrStub("removed-provider")

        assertNotNull(stub)
        val error = runCatching { stub.get("record") }.exceptionOrNull()
        assertTrue(error is ProviderException.Unavailable)
    }

    @Test
    fun `community provider cannot shadow a built-in ID`() {
        val manager = MutableProviderManager(listOf(provider("arxiv")))

        assertThrows(IllegalArgumentException::class.java) {
            manager.register(provider("arxiv"), ProviderOrigin.COMMUNITY_PLUGIN, "org.attacker", 1)
        }
    }

    private fun provider(id: String): PaperProvider = object : PaperProvider {
        override val descriptor = ProviderDescriptor(id, id, 0)
        override suspend fun search(query: PaperSearchQuery) = ProviderPage(emptyList())
        override suspend fun get(recordId: String): RemotePaper? = null
    }
}
