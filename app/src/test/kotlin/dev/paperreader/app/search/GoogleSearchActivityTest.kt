package dev.paperreader.app.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleSearchActivityTest {
    @Test
    fun `navigation allowlist accepts only google https`() {
        assertTrue("https://www.google.com/search?q=paper".isAllowedGoogleNavigation())
        assertFalse("http://www.google.com/search?q=paper".isAllowedGoogleNavigation())
        assertFalse("https://accounts.google.com/".isAllowedGoogleNavigation())
        assertFalse("https://google.com@attacker.example/".isAllowedGoogleNavigation())
        assertFalse("https://attacker.example/google.com".isAllowedGoogleNavigation())
    }

    @Test
    fun `resource allowlist accepts google static assets but not ads`() {
        assertTrue("https://www.gstatic.com/images/branding.png".isAllowedGoogleResource())
        assertTrue("https://encrypted-tbn0.gstatic.com/image?q=1".isAllowedGoogleResource())
        assertFalse("https://googleadservices.com/pagead".isAllowedGoogleResource())
        assertFalse("https://doubleclick.net/track".isAllowedGoogleResource())
    }

    @Test
    fun `arxiv handoff accepts paper routes only`() {
        assertTrue("https://arxiv.org/abs/1706.03762".isArxivPaperUrl())
        assertTrue("https://arxiv.org/pdf/1706.03762v7.pdf".isArxivPaperUrl())
        assertFalse("https://arxiv.org/list/cs.AI/recent".isArxivPaperUrl())
        assertFalse("https://arxiv.org/abs/not-an-id".isArxivPaperUrl())
        assertFalse("https://arxiv.org/abs/1706.03762?download=1".isArxivPaperUrl())
        assertFalse("https://arxiv.org@evil.example/abs/1706.03762".isArxivPaperUrl())
        assertFalse("https://evil.example/abs/1706.03762".isArxivPaperUrl())
    }

    @Test
    fun `google result redirect is canonicalized before native handoff`() {
        assertTrue(
            "https://www.google.com/url?q=https%3A%2F%2Farxiv.org%2Fpdf%2F1706.03762v7.pdf&sa=U"
                .arxivResultUrlOrNull() == "https://arxiv.org/abs/1706.03762v7",
        )
        assertFalse(
            "https://www.google.com/url?q=https%3A%2F%2Fevil.example%2Fabs%2F1706.03762"
                .arxivResultUrlOrNull() != null,
        )
        assertFalse("https://www.google.com/url?q=%E0%A4%A".arxivResultUrlOrNull() != null)
    }
}
