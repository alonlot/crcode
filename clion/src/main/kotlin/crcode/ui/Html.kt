package crcode.ui

import com.intellij.openapi.util.text.StringUtil

/** A small markdown to HTML conversion for notes: code fences, inline code, bold, italics, links and lists. */
object Html {
    fun esc(s: String): String = StringUtil.escapeXmlEntities(s)

    fun markdown(md: String): String {
        val out = StringBuilder()
        val lines = md.replace("\r\n", "\n").split("\n")
        var i = 0
        var inList = false
        fun closeList() {
            if (inList) {
                out.append("</ul>")
                inList = false
            }
        }
        while (i < lines.size) {
            val line = lines[i]
            if (line.trimStart().startsWith("```")) {
                closeList()
                val lang = line.trim().removePrefix("```").trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code.append(lines[i]).append('\n')
                    i++
                }
                val label = if (lang == "suggestion" || lang.startsWith("suggestion:")) "<div><i>Suggested change:</i></div>" else ""
                out.append(label).append("<pre>").append(esc(code.toString().trimEnd())).append("</pre>")
                i++
                continue
            }
            val bullet = Regex("^\\s*[-*+]\\s+(.*)").find(line)
            if (bullet != null) {
                if (!inList) {
                    out.append("<ul>")
                    inList = true
                }
                out.append("<li>").append(inline(bullet.groupValues[1])).append("</li>")
            } else if (line.isBlank()) {
                closeList()
                out.append("<br>")
            } else {
                closeList()
                val h = Regex("^(#{1,6})\\s+(.*)").find(line)
                if (h != null) out.append("<b>").append(inline(h.groupValues[2])).append("</b><br>")
                else out.append(inline(line)).append("<br>")
            }
            i++
        }
        closeList()
        return out.toString().removeSuffix("<br>")
    }

    private fun inline(s: String): String {
        val parts = s.split("`")
        return parts.mapIndexed { idx, p ->
            if (idx % 2 == 1 && idx < parts.size - 1) "<code>${esc(p)}</code>"
            else esc(p)
                .replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
                .replace(Regex("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])"), "<i>$1</i>")
                .replace(Regex("\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)"), "<a href=\"$2\">$1</a>")
        }.joinToString("")
    }
}
