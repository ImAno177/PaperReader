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
        manager.updateOrphaned(listOf(OrphanedProviderPlugin("org.example.old", "Old source", 1)))

        assertEquals(setOf("arxiv", "community"), manager.state.value.installed.map { it.descriptor.id }.toSet())
        assertEquals("org.example.new", manager.state.value.available.single().packageName)
        assertEquals("Signer mismatch", manager.state.value.untrusted.single().reason)
        assertEquals("org.example.old", manager.state.value.orphaned.single().packageName)

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

    @Test
    fun `community providers cannot shadow each other and can be reconciled as one origin`() {
        val manager = MutableProviderManager(listOf(provider("arxiv")))
        manager.register(provider("community"), ProviderOrigin.COMMUNITY_PLUGIN, "org.example.first", 1)

        assertThrows(IllegalArgumentException::class.java) {
            manager.register(provider("community"), ProviderOrigin.COMMUNITY_PLUGIN, "org.example.second", 2)
        }

        manager.unregisterByOrigin(ProviderOrigin.COMMUNITY_PLUGIN)
        assertEquals(setOf("arxiv"), manager.state.value.installed.map { it.descriptor.id }.toSet())
    }

    @Test
    fun `disabled providers remain installed but are excluded from federated participation`() {
        val manager = MutableProviderManager(listOf(provider("semanticscholar"), provider("arxiv")))

        manager.setDisabledProviderIds(setOf("arxiv"))

        assertEquals(setOf("semanticscholar", "arxiv"), manager.state.value.installed.map { it.descriptor.id }.toSet())
        assertEquals(setOf("arxiv"), manager.state.value.disabledProviderIds)
        assertEquals(listOf("semanticscholar"), manager.getAll().map { it.descriptor.id })
        assertNotNull(manager.get("arxiv"))
    }

    private fun provider(id: String): PaperProvider = object : PaperProvider {
        override val descriptor = ProviderDescriptor(id, id, 0)
        override suspend fun search(query: PaperSearchQuery) = ProviderPage(emptyList())
        override suspend fun get(recordId: String): RemotePaper? = null
    }
}
