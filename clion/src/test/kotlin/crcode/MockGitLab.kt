package crcode

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Collections

/** A small GitLab API for the integration test, with the same data as the VS Code end-to-end mock. */
class MockGitLab(private val sha: String) {
    val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val port get() = server.address.port
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    var pipelineStatus = "failed"
    var pipelineId = 50
    private var nextId = 100L
    private val gson = Gson()
    private val rev = mapOf("id" to 1, "username" to "rev", "name" to "Reviewer")
    private val me = mapOf("id" to 2, "username" to "me", "name" to "Me")

    private fun note(id: Long, body: String, author: Map<String, Any> = rev, position: Int? = null, resolvable: Boolean = true, resolved: Boolean = false) =
        mutableMapOf<String, Any?>(
            "id" to id, "body" to body, "author" to author, "created_at" to "2026-10-01T10:00:00Z", "system" to false,
            "resolvable" to resolvable, "resolved" to resolved,
            "position" to position?.let { mapOf("position_type" to "text", "new_path" to "src/a.ts", "old_path" to "src/a.ts", "new_line" to it, "head_sha" to sha) },
        )

    val discussions = mutableListOf(
        mapOf("id" to "d1", "individual_note" to false, "notes" to mutableListOf(note(1, "Rename v2 please", position = 2))),
        mapOf("id" to "d2", "individual_note" to false, "notes" to mutableListOf(note(2, "v5 is wrong", position = 5))),
        mapOf("id" to "d3", "individual_note" to false, "notes" to mutableListOf(note(3, "General: add tests", resolvable = false))),
        mapOf("id" to "d6", "individual_note" to false, "notes" to mutableListOf(note(6, "why v3?", position = 3), note(7, "Because of X", author = me))),
        mapOf("id" to "d5", "individual_note" to false, "notes" to mutableListOf(note(5, "old, resolved", position = 3, resolved = true))),
    )

    private fun jobs() = listOf(
        mapOf("id" to 501, "name" to "build", "stage" to "build", "status" to "success", "web_url" to "http://x/j/501", "allow_failure" to false, "duration" to 61),
        mapOf("id" to 502, "name" to "test", "stage" to "test", "status" to if (pipelineStatus == "running") "running" else pipelineStatus, "web_url" to "http://x/j/502", "allow_failure" to false, "failure_reason" to "script_failure"),
        mapOf("id" to 503, "name" to "lint", "stage" to "test", "status" to "failed", "web_url" to "http://x/j/503", "allow_failure" to true, "duration" to 12.2),
        mapOf("id" to 504, "name" to "unit", "stage" to "test", "status" to "success", "web_url" to "http://x/j/504", "allow_failure" to false, "duration" to 95),
        mapOf("id" to 505, "name" to "deploy-staging", "stage" to "deploy", "status" to "manual", "web_url" to "http://x/j/505", "allow_failure" to false),
        mapOf("id" to 506, "name" to "deploy-prod", "stage" to "deploy", "status" to "skipped", "web_url" to "http://x/j/506", "allow_failure" to false),
    )

    private fun pipeline() = mapOf(
        "id" to pipelineId, "status" to pipelineStatus, "ref" to "12-feature", "sha" to sha, "web_url" to "http://x/p/$pipelineId",
        "duration" to 254, "user" to mapOf("name" to "Dana Dev"), "source" to "merge_request_event",
    )

    fun start(): MockGitLab {
        server.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val u = ex.requestURI.toString()
            requests.add("${ex.requestMethod} $u")
            var status = 200
            val path = u.substringBefore('?')
            val out: Any? = when {
                path == "/api/v4/user" -> me
                path.endsWith("/merge_requests") && u.contains("source_branch=") -> if (u.contains("feature")) listOf(mapOf("iid" to 7)) else emptyList<Any>()
                path.endsWith("/merge_requests/7") -> mapOf(
                    "iid" to 7, "title" to "Feature X", "web_url" to "http://x/mr/7", "source_branch" to "12-feature", "target_branch" to "main",
                    "diff_refs" to mapOf("base_sha" to "b", "head_sha" to sha, "start_sha" to "s"),
                )
                path.endsWith("/merge_requests/7/discussions") && ex.requestMethod == "GET" -> discussions
                Regex(".*/discussions/(\\w+)/notes$").matches(path) && ex.requestMethod == "POST" -> {
                    val id = Regex(".*/discussions/(\\w+)/notes$").find(path)!!.groupValues[1]
                    val n = note(nextId++, JsonParser.parseString(body).asJsonObject.get("body").asString, author = me)
                    @Suppress("UNCHECKED_CAST")
                    (discussions.first { it["id"] == id }["notes"] as MutableList<Any>).add(n)
                    n
                }
                path.endsWith("/merge_requests/7/pipelines") -> listOf(pipeline())
                Regex(".*/pipelines/\\d+$").matches(path) -> pipeline()
                Regex(".*/pipelines/\\d+/jobs$").matches(path) -> jobs()
                Regex(".*/(jobs|pipelines)/\\d+/(retry|play|cancel)$").matches(path) -> mapOf("id" to 1, "status" to "pending")
                Regex(".*/jobs/\\d+/trace$").matches(path) -> {
                    val id = Regex(".*/jobs/(\\d+)/trace$").find(path)!!.groupValues[1]
                    "\u001b[0Ksection_start:1:step\r\u001b[0K$ npm test\nFAIL src/a.ts line 4 is wrong (job $id)\nsection_end:1:step\r\n"
                }
                else -> {
                    status = 404
                    mapOf("message" to "404 Not Found")
                }
            }
            val bytes = (if (out is String) out else gson.toJson(out)).toByteArray()
            ex.responseHeaders.add("Content-Type", if (out is String) "text/plain" else "application/json")
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        return this
    }

    fun stop() = server.stop(0)
}
