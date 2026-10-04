package io.itermux.app.ui

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PrivacyPolicyTest {
    @Test fun eachLanguageGetsOnlyItsOwnSection() {
        val markdown = "# 隐私政策\n\n中文\n\n## 联系\n\n# Privacy Policy\n\nEnglish\n"
        assertEquals("# 隐私政策\n\n中文\n\n## 联系", privacyPolicySection(markdown, english = false))
        assertEquals("# Privacy Policy\n\nEnglish", privacyPolicySection(markdown, english = true))
    }

    @Test fun singleSectionIsShownForBothLanguages() {
        assertEquals("# Policy\n", privacyPolicySection("# Policy\n", english = true))
    }

    @Test fun shippedPolicyHasChineseThenEnglish() {
        val markdown = File("../docs/PRIVACY.md").readText()
        assertTrue(privacyPolicySection(markdown, english = false).startsWith("# Roamtty 隐私政策"))
        assertTrue(privacyPolicySection(markdown, english = true).startsWith("# Roamtty Privacy Policy"))
    }
}
