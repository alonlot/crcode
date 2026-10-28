package crcode.core

import java.net.URI

data class ParsedRemote(val host: String, val path: String, val guessedBaseUrl: String)

/**
 * Parses remotes such as:
 *   git@gitlab.example.com:group/sub/project.git
 *   ssh://git@gitlab.example.com:2222/group/project.git
 *   https://gitlab.example.com/group/project.git
 *   https://user:token@gitlab.example.com/gitlab/group/project
 */
fun parseRemote(url: String): ParsedRemote? {
    val scp = Regex("^(?:[^@/]+@)?([^:/]+):(?!/)(.+?)(?:\\.git)?/?$").find(url)
    if (scp != null && !Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE).containsMatchIn(url)) {
        return ParsedRemote(scp.groupValues[1], scp.groupValues[2], "https://${scp.groupValues[1]}")
    }
    return try {
        val u = URI(url)
        val host = u.host ?: return null
        val path = (u.path ?: "").trim('/').removeSuffix(".git")
        if (path.isEmpty()) return null
        val port = if (u.port != -1) ":${u.port}" else ""
        val base = when (u.scheme) {
            "http" -> "http://$host$port"
            "https" -> "https://$host$port"
            else -> "https://$host"
        }
        ParsedRemote(host, path, base)
    } catch (_: Exception) {
        null
    }
}

/** Removes a relative URL root (for example `/gitlab`) of the base URL from a project path. */
fun stripBasePath(projectPath: String, baseUrl: String): String {
    val prefix = try {
        (URI(baseUrl).path ?: "").trim('/')
    } catch (_: Exception) {
        ""
    }
    return if (prefix.isNotEmpty() && projectPath.startsWith("$prefix/")) projectPath.substring(prefix.length + 1) else projectPath
}
