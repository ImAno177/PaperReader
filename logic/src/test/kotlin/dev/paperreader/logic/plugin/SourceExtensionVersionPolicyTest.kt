package dev.paperreader.logic.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceExtensionVersionPolicyTest {
    @Test
    fun `only an installed version below the signed minimum is update-remediable`() {
        requireTrustedSourceVersion(installedVersionCode = 3, minimumVersionCode = 2, maximumVersionCode = 4)

        val revoked = assertThrows(InstalledSourceVersionOutOfRangeException::class.java) {
            requireTrustedSourceVersion(installedVersionCode = 1, minimumVersionCode = 2, maximumVersionCode = 4)
        }
        assertEquals(1L, revoked.installedVersionCode)
        assertEquals(2L, revoked.minimumVersionCode)
        assertTrue(revoked.updateCanRemediate)

        val newerThanCatalog = assertThrows(InstalledSourceVersionOutOfRangeException::class.java) {
            requireTrustedSourceVersion(installedVersionCode = 5, minimumVersionCode = 2, maximumVersionCode = 4)
        }
        assertEquals(5L, newerThanCatalog.installedVersionCode)
        assertEquals(4L, newerThanCatalog.maximumVersionCode)
        assertFalse(newerThanCatalog.updateCanRemediate)
    }
}
