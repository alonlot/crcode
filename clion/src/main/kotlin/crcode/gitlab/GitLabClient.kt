package crcode.gitlab

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.util.net.ssl.CertificateManager
import crcode.core.CiJobLike
import crcode.core.int
import crcode.core.obj
import crcode.core.str
import java.net.ProxySelector
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.cert.X509Certificate
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

data class GitLabUser(val id: Long, val username: String, val name: String, val avatarUrl: String?)

data class DiffRefs(val baseSha: String, val headSha: String, val startSha: String)

data class MergeRequest(
    val iid: Int,
    val title: String,
    val webUrl: String,
    val sourceBranch: String,
    val targetBranch: String,
    val diffRefs: DiffRefs?,
)

data class NotePosition(val positionType: String?, val newPath: String?, val oldPath: String?, val newLine: Int?, val oldLine: Int?, val headSha: String?)

data class Note(
    val id: Long,
    val body: String,
    val author: GitLabUser,
    val createdAt: String?,
    val system: Boolean,
    val resolvable: Boolean,
    val resolved: Boolean,
    val position: NotePosition?,
)

data class Discussion(val id: String, val notes: List<Note>)

data class Pipeline(
    val id: Long,
    val status: String,
    val ref: String,
    val sha: String,
    val webUrl: String,
    val source: String? = null,
    val createdAt: String? = null,
    val duration: Double? = null,
    val userName: String? = null,
)

data class Job(
    override val id: Long,
    override val name: String,
    override val stage: String,
    override val status: String,
    val webUrl: String,
    override val allowFailure: Boolean,
    val failureReason: String?,
    val duration: Double?,
    val queuedDuration: Double?,
) : CiJobLike

class GitLabException(message: String, val status: Int? = null) : Exception(message)

class GitLabClient(baseUrl: String, private val token: String, strictSSL: Boolean) {
    private val apiBase = baseUrl.trimEnd('/') + "/api/v4/"
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .proxy(ProxySelector.getDefault() ?: HttpClient.Builder.NO_PROXY)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .sslContext(if (strictSSL) ideSslContext() else trustAllContext())
        .build()

    private data class Response(val status: Int, val headers: Map<String, List<String>>, val body: String)

    private fun request(method: String, path: String, body: JsonElement? = null): Response {
        val builder = HttpRequest.newBuilder(URI.create(apiBase + path.trimStart('/')))
            .timeout(Duration.ofSeconds(30))
            .header("PRIVATE-TOKEN", token)
            .header("Accept", "application/json")
        if (body != null) {
            builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body.toString()))
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        }
        val res = try {
            http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw GitLabException("GitLab request failed: ${e.message ?: e.javaClass.simpleName}")
        }
        return Response(res.statusCode(), res.headers().map(), res.body() ?: "")
    }

    private fun json(method: String, path: String, body: JsonElement? = null): JsonElement {
        val res = request(method, path, body)
        if (res.status !in 200..299) {
            val detail = try {
                val o = JsonParser.parseString(res.body).asJsonObject
                o.get("message")?.toString() ?: o.str("error") ?: res.body
            } catch (_: Exception) {
                res.body
            }
            val hint = when (res.status) {
                401 -> " The token is wrong or expired. Use \"Set GitLab Access Token\"."
                403 -> " The token has no permission for this action (it needs the api scope)."
                else -> ""
            }
            throw GitLabException("GitLab $method $path returned ${res.status}: ${detail.take(300)}$hint", res.status)
        }
        return if (res.body.isBlank()) JsonObject() else JsonParser.parseString(res.body)
    }

    private fun paginate(path: String): List<JsonObject> {
        val all = mutableListOf<JsonObject>()
        var page = "1"
        while (page.isNotEmpty()) {
            val sep = if ('?' in path) "&" else "?"
            val res = request("GET", "$path${sep}per_page=100&page=$page")
            if (res.status !in 200..299) throw GitLabException("GitLab GET $path returned ${res.status}: ${res.body.take(300)}", res.status)
            JsonParser.parseString(res.body).asJsonArray.forEach { all.add(it.asJsonObject) }
            page = res.headers.entries.firstOrNull { it.key.equals("x-next-page", true) }?.value?.firstOrNull()?.trim() ?: ""
        }
        return all
    }

    private fun p(project: String) = URLEncoder.encode(project, Charsets.UTF_8)

    fun currentUser(): GitLabUser = user(json("GET", "user").asJsonObject)

    fun findMergeRequest(project: String, sourceBranch: String): MergeRequest? {
        val list = json("GET", "projects/${p(project)}/merge_requests?state=opened&source_branch=${URLEncoder.encode(sourceBranch, Charsets.UTF_8)}").asJsonArray
        val iid = list.firstOrNull()?.asJsonObject?.int("iid") ?: return null
        // The list endpoint does not include diff_refs.
        return mergeRequest(json("GET", "projects/${p(project)}/merge_requests/$iid").asJsonObject)
    }

    fun discussions(project: String, iid: Int): List<Discussion> =
        paginate("projects/${p(project)}/merge_requests/$iid/discussions").map { d ->
            Discussion(d.str("id") ?: "", (d.get("notes") as? JsonArray)?.map { note(it.asJsonObject) } ?: emptyList())
        }

    fun addNote(project: String, iid: Int, discussionId: String, body: String): Note =
        note(json("POST", "projects/${p(project)}/merge_requests/$iid/discussions/$discussionId/notes", JsonObject().apply { addProperty("body", body) }).asJsonObject)

    fun setResolved(project: String, iid: Int, discussionId: String, resolved: Boolean) {
        json("PUT", "projects/${p(project)}/merge_requests/$iid/discussions/$discussionId?resolved=$resolved")
    }

    fun createDiscussion(project: String, iid: Int, body: String, position: Map<String, Any>? = null) {
        val o = JsonObject().apply {
            addProperty("body", body)
            if (position != null) add("position", Gson().toJsonTree(position))
        }
        json("POST", "projects/${p(project)}/merge_requests/$iid/discussions", o)
    }

    /** Pipelines of the merge request, newest first. */
    fun mergeRequestPipelines(project: String, iid: Int): List<Pipeline> =
        json("GET", "projects/${p(project)}/merge_requests/$iid/pipelines").asJsonArray.map { pipeline(it.asJsonObject) }

    fun pipeline(project: String, id: Long): Pipeline = pipeline(json("GET", "projects/${p(project)}/pipelines/$id").asJsonObject)

    fun pipelineJobs(project: String, pipelineId: Long): List<Job> =
        paginate("projects/${p(project)}/pipelines/$pipelineId/jobs?include_retried=false").map { job(it) }

    fun retryJob(project: String, id: Long) { json("POST", "projects/${p(project)}/jobs/$id/retry") }
    fun playJob(project: String, id: Long) { json("POST", "projects/${p(project)}/jobs/$id/play") }
    fun cancelJob(project: String, id: Long) { json("POST", "projects/${p(project)}/jobs/$id/cancel") }
    fun retryPipeline(project: String, id: Long) { json("POST", "projects/${p(project)}/pipelines/$id/retry") }
    fun cancelPipeline(project: String, id: Long) { json("POST", "projects/${p(project)}/pipelines/$id/cancel") }

    /** Raw log of a job (plain text). */
    fun jobTrace(project: String, jobId: Long): String {
        val res = request("GET", "projects/${p(project)}/jobs/$jobId/trace")
        if (res.status !in 200..299) throw GitLabException("GitLab GET jobs/$jobId/trace returned ${res.status}: ${res.body.take(300)}", res.status)
        return res.body
    }

    companion object {
        private fun bool(o: JsonObject, k: String) = o.get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false
        private fun long(o: JsonObject, k: String) = o.get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
        private fun dbl(o: JsonObject, k: String) = o.get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble

        fun user(o: JsonObject) = GitLabUser(long(o, "id") ?: -1, o.str("username") ?: "", o.str("name") ?: o.str("username") ?: "?", o.str("avatar_url"))

        fun mergeRequest(o: JsonObject) = MergeRequest(
            o.int("iid") ?: 0,
            o.str("title") ?: "",
            o.str("web_url") ?: "",
            o.str("source_branch") ?: "",
            o.str("target_branch") ?: "main",
            o.obj("diff_refs")?.let { d -> DiffRefs(d.str("base_sha") ?: "", d.str("head_sha") ?: "", d.str("start_sha") ?: "") },
        )

        fun note(o: JsonObject) = Note(
            long(o, "id") ?: -1,
            o.str("body") ?: "",
            o.obj("author")?.let { user(it) } ?: GitLabUser(-1, "", "?", null),
            o.str("created_at"),
            bool(o, "system"),
            bool(o, "resolvable"),
            bool(o, "resolved"),
            o.obj("position")?.let { pos ->
                NotePosition(pos.str("position_type"), pos.str("new_path"), pos.str("old_path"), pos.int("new_line"), pos.int("old_line"), pos.str("head_sha"))
            },
        )

        fun pipeline(o: JsonObject) = Pipeline(
            long(o, "id") ?: -1,
            o.str("status") ?: "created",
            o.str("ref") ?: "",
            o.str("sha") ?: "",
            o.str("web_url") ?: "",
            o.str("source"),
            o.str("created_at"),
            dbl(o, "duration"),
            o.obj("user")?.str("name"),
        )

        fun job(o: JsonObject) = Job(
            long(o, "id") ?: -1,
            o.str("name") ?: "?",
            o.str("stage") ?: "?",
            o.str("status") ?: "created",
            o.str("web_url") ?: "",
            bool(o, "allow_failure"),
            o.str("failure_reason"),
            dbl(o, "duration"),
            dbl(o, "queued_duration"),
        )

        /** Uses the IDE certificate store, so certificates that the user accepted in the IDE also work here. */
        private fun ideSslContext(): SSLContext = try {
            CertificateManager.getInstance().sslContext
        } catch (_: Throwable) {
            SSLContext.getDefault()
        }

        private fun trustAllContext(): SSLContext {
            val tm = object : X509ExtendedTrustManager() {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: java.net.Socket?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: java.net.Socket?) {}
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            return SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        }
    }
}
