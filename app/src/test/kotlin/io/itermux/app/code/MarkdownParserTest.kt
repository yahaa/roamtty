package io.itermux.app.code

import org.junit.Assert.*
import org.junit.Test

class MarkdownParserTest {
    private fun MdText.styled(style: MdStyle) = spans.filter { it.style == style }.map { text.substring(it.start, it.end) }

    @Test fun headingsParagraphsAndSetext() {
        val blocks = MarkdownParser.parse("# Title #\n\nFirst line\nsame paragraph\n\nSub\n---\n\nBig\n===")
        assertEquals(MdBlock.Heading(1, MdText("Title")), blocks[0])
        assertEquals("First line same paragraph", (blocks[1] as MdBlock.Paragraph).text.text)
        assertEquals(MdBlock.Heading(2, MdText("Sub")), blocks[2])
        assertEquals(MdBlock.Heading(1, MdText("Big")), blocks[3])
    }

    @Test fun hardBreaksUseTrailingSpacesOrBackslash() {
        val text = (MarkdownParser.parse("one  \ntwo\\\nthree")[0] as MdBlock.Paragraph).text.text
        assertEquals("one\ntwo\nthree", text)
    }

    @Test fun inlineStylesLinksAndEscapes() {
        val text = (MarkdownParser.parse("**bold** *it* ~~gone~~ `a*b` [site](https://x.dev \"t\") \\*lit\\* snake_case_name <https://y.dev> see https://z.dev.")[0]
            as MdBlock.Paragraph).text
        assertEquals("bold it gone a*b site *lit* snake_case_name https://y.dev see https://z.dev.", text.text)
        assertEquals(listOf("bold"), text.styled(MdStyle.BOLD))
        assertEquals(listOf("it"), text.styled(MdStyle.ITALIC))
        assertEquals(listOf("gone"), text.styled(MdStyle.STRIKE))
        assertEquals(listOf("a*b"), text.styled(MdStyle.CODE))
        assertEquals(listOf("site", "https://y.dev", "https://z.dev"), text.styled(MdStyle.LINK))
        assertEquals("https://x.dev", text.spans.first { it.style == MdStyle.LINK }.url)
    }

    @Test fun nestedEmphasisInsideLinks() {
        val text = (MarkdownParser.parse("[**docs** page](./docs/README.md)")[0] as MdBlock.Paragraph).text
        assertEquals("docs page", text.text)
        assertEquals(listOf("docs"), text.styled(MdStyle.BOLD))
        assertEquals("./docs/README.md", text.spans.single { it.style == MdStyle.LINK }.url)
    }

    @Test fun referenceLinksResolveAndDefinitionsDisappear() {
        val blocks = MarkdownParser.parse("Read [the guide][g] or [g].\n\n[g]: https://guide.dev \"Guide\"")
        assertEquals(1, blocks.size)
        val text = (blocks[0] as MdBlock.Paragraph).text
        assertEquals("Read the guide or g.", text.text)
        assertEquals(listOf("https://guide.dev", "https://guide.dev"), text.spans.map { it.url })
    }

    @Test fun fencedAndIndentedCode() {
        val blocks = MarkdownParser.parse("```kotlin title\nval x = 1\n\nprintln(x)\n```\n\n    indented\n      more")
        assertEquals(MdBlock.Code("kotlin", "val x = 1\n\nprintln(x)"), blocks[0])
        assertEquals(MdBlock.Code("", "indented\n  more"), blocks[1])
    }

    @Test fun nestedTaskAndOrderedLists() {
        val blocks = MarkdownParser.parse("- a\n  continued\n  - b\n    - [x] done\n- [ ] todo\n\n3. third\n4) fourth").map { it as MdBlock.ListItem }
        assertEquals(listOf(0, 1, 2, 0, 0, 0), blocks.map { it.depth })
        assertEquals("a continued", blocks[0].text.text)
        assertEquals(true, blocks[2].checked)
        assertEquals("done", blocks[2].text.text)
        assertEquals(false, blocks[3].checked)
        assertEquals(listOf("3.", "4."), blocks.drop(4).map { it.marker })
    }

    @Test fun quotesContainBlocks() {
        val quote = MarkdownParser.parse("> # Note\n> body **text**\nlazy line")[0] as MdBlock.Quote
        assertEquals(MdBlock.Heading(1, MdText("Note")), quote.blocks[0])
        assertEquals("body text lazy line", (quote.blocks[1] as MdBlock.Paragraph).text.text)
    }

    @Test fun tablesWithAlignmentEscapesAndShortRows() {
        val table = MarkdownParser.parse("| Name | Size | Note |\n|:--|--:|:-:|\n| `a|b` | 1 | x \\| y |\n| c |")[0] as MdBlock.Table
        assertEquals(listOf("Name", "Size", "Note"), table.header.map { it.text })
        assertEquals(listOf(MdAlign.START, MdAlign.END, MdAlign.CENTER), table.alignments)
        assertEquals(listOf("a|b", "1", "x | y"), table.rows[0].map { it.text })
        assertEquals(listOf("c", "", ""), table.rows[1].map { it.text })
    }

    @Test fun imagesRulesAndReadmeHtml() {
        val blocks = MarkdownParser.parse("![logo](img/logo.png \"Logo\")\n\n---\n\n<p align=\"center\">\n  <img src=\"docs/shot.png\" alt=\"Shot\" width=\"300\">\n  <br>Mobile <b>SSH</b>\n</p>\n\n<!-- hidden\ncomment -->")
        assertEquals(MdBlock.Image("logo", "img/logo.png"), blocks[0])
        assertEquals(MdBlock.Rule, blocks[1])
        assertEquals(MdBlock.Image("Shot", "docs/shot.png"), blocks[2])
        assertEquals("Mobile SSH", (blocks[3] as MdBlock.Paragraph).text.text)
        assertEquals(4, blocks.size)
    }

    @Test fun parsesLargeDocumentsQuickly() {
        val source = buildString { repeat(4000) { append("## Section $it\n\nSome **bold** text with `code` and [a link](https://x.dev).\n\n- item\n- item\n\n") } }
        val started = System.nanoTime()
        val blocks = MarkdownParser.parse(source)
        assertEquals(4000 * 4, blocks.size)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3000)
    }
}
