package crcode.opencode

import com.google.gson.JsonElement
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import crcode.core.extractJson
import crcode.core.parseOpencodeEvents
import crcode.settings.CrSettings
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

open class OpencodeException(message: String) : Exception(message)

/** The opencode executable cannot be started. Retrying does not help until the path changes. */
class OpencodeNotFoundException(message: String, val path: String) : OpencodeException(message)

/** Runs `opencode run --format json` with the prompt on stdin. Blocks; call from a background thread. */
class OpencodeRunner(private val log: (String) -> Unit) {

    fun askJson(cwd: String, prompt: String, indicator: ProgressIndicator?): JsonElement = extractJson(ask(cwd, prompt, indicator))

    fun ask(cwd: String, prompt: String, indicator: ProgressIndicator?): String {
        val s = CrSettings.app().state
        val exe = s.opencodePath.ifBlank { "opencode" }
        val args = mutableListOf(exe, "run", "--format", "json")
        if (s.opencodeModel.isNotBlank()) args += listOf("--model", s.opencodeModel)
        if (s.opencodeAgent.isNotBlank()) args += listOf("--agent", s.opencodeAgent)
        // The prompt goes through stdin (opencode appends it to the message), so long diffs do not hit argv limits.
        args += "Do the task described in this message."
        log("\n$ ${args.joinToString(" ")}   (cwd: $cwd, prompt: ${prompt.length} chars)")

        val process = try {
            ProcessBuilder(args).directory(File(cwd)).start()
        } catch (e: IOException) {
            if (e.message?.contains("error=2") == true || e.message?.contains("No such file") == true) {
                throw OpencodeNotFoundException("Cannot find '$exe'. Install opencode or set the opencode path in Settings | Tools | GitLab CR + opencode.", exe)
            }
            throw OpencodeException("Cannot start opencode: ${e.message}")
        }
        val out = StringBuilder()
        val err = StringBuilder()
        val reader = Thread { out.append(process.inputStream.bufferedReader().readText()) }.apply { start() }
        val errReader = Thread { err.append(process.errorStream.bufferedReader().readText()) }.apply { start() }
        try {
            process.outputStream.bufferedWriter().use { it.write(prompt) }
        } catch (_: IOException) {
            // the process can exit before it reads stdin; the exit code reports it
        }
        val deadline = System.currentTimeMillis() + maxOf(10, s.opencodeTimeoutSeconds) * 1000L
        while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
            if (indicator?.isCanceled == true) {
                process.destroyForcibly()
                throw ProcessCanceledException()
            }
            if (System.currentTimeMillis() > deadline) {
                process.destroyForcibly()
                throw OpencodeException("opencode did not answer in ${s.opencodeTimeoutSeconds} s")
            }
        }
        reader.join(); errReader.join()
        if (err.isNotBlank()) log(err.toString().trimEnd())
        val parsed = parseOpencodeEvents(out.toString())
        log(parsed.text.ifEmpty { "(no text output)" })
        if (parsed.errors.isNotEmpty()) throw OpencodeException("opencode error: " + parsed.errors.joinToString("; "))
        if (parsed.text.isBlank()) throw OpencodeException("opencode returned no answer (exit code ${process.exitValue()}). ${err.toString().trim().takeLast(500)}")
        return parsed.text
    }
}
