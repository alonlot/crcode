package crcode.core

import kotlin.math.roundToLong

/** GitLab job and pipeline statuses, plus `warning` for a failed job that is allowed to fail (as GitLab shows it). */
object CiStatus {
    const val WARNING = "warning"
    val ACTIVE = setOf("created", "waiting_for_resource", "preparing", "pending", "running")
    fun isActive(status: String) = status in ACTIVE
}

interface CiJobLike {
    val id: Long
    val name: String
    val stage: String
    val status: String
    val allowFailure: Boolean
}

fun shownStatus(job: CiJobLike): String = if (job.status == "failed" && job.allowFailure) CiStatus.WARNING else job.status

data class CiStage<J : CiJobLike>(val name: String, val status: String, val jobs: List<J>)

/** Groups jobs by stage in the order the stages appear, and computes the stage status like GitLab. */
fun <J : CiJobLike> groupStages(jobs: List<J>): List<CiStage<J>> {
    val byStage = LinkedHashMap<String, MutableList<J>>()
    for (j in jobs) byStage.getOrPut(j.stage) { mutableListOf() }.add(j)
    return byStage.map { (name, list) -> CiStage(name, stageStatus(list), list) }
}

fun stageStatus(jobs: List<CiJobLike>): String {
    fun has(vararg st: String) = jobs.any { it.status in st }
    return when {
        has("running") -> "running"
        has("pending", "created", "waiting_for_resource", "preparing") -> "pending"
        jobs.any { it.status == "failed" && !it.allowFailure } -> "failed"
        has("canceled") -> "canceled"
        has("manual") -> "manual"
        has("scheduled") -> "scheduled"
        jobs.any { it.status == "failed" && it.allowFailure } -> CiStatus.WARNING
        jobs.isNotEmpty() && jobs.all { it.status == "skipped" } -> "skipped"
        else -> "success"
    }
}

fun statusLabel(status: String): String = when (status) {
    CiStatus.WARNING -> "failed (allowed to fail)"
    else -> status.replace('_', ' ')
}

fun formatDuration(seconds: Double?): String {
    if (seconds == null || seconds.isNaN() || seconds.isInfinite()) return ""
    val s = seconds.roundToLong()
    if (s < 60) return "${s}s"
    val m = s / 60
    if (m < 60) return "${m}m ${s % 60}s"
    return "${m / 60}h ${m % 60}m"
}

enum class JobAction { RETRY, PLAY, CANCEL }

/** Which actions GitLab offers for a job in this status. */
fun jobActions(status: String): List<JobAction> = when (status) {
    "failed", "canceled", "success" -> listOf(JobAction.RETRY)
    "manual" -> listOf(JobAction.PLAY)
    "running", "pending" -> listOf(JobAction.CANCEL)
    else -> emptyList()
}
