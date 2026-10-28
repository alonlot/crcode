package crcode.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import crcode.core.CiStatus
import crcode.core.CrThread
import crcode.core.ThreadSource
import java.awt.Color
import javax.swing.Icon

object CrIcons {
    fun status(status: String): Icon = when (status) {
        "success" -> AllIcons.RunConfigurations.TestPassed
        "failed" -> AllIcons.RunConfigurations.TestFailed
        CiStatus.WARNING -> AllIcons.General.Warning
        "running" -> AnimatedIcon.Default.INSTANCE
        "pending", "waiting_for_resource", "preparing" -> AllIcons.RunConfigurations.TestPaused
        "canceled" -> AllIcons.RunConfigurations.TestTerminated
        "skipped" -> AllIcons.RunConfigurations.TestSkipped
        "manual" -> AllIcons.Actions.Execute
        "scheduled" -> AllIcons.Vcs.History
        else -> AllIcons.RunConfigurations.TestNotRan
    }

    /** GitLab status colors, for the graph badge and the status bar. */
    fun color(status: String): Color = when (status) {
        "success" -> JBColor(0x108548, 0x2DA160)
        "failed" -> JBColor(0xDD2B0E, 0xEC5941)
        "running" -> JBColor(0x1F75CB, 0x428FDC)
        CiStatus.WARNING, "pending", "waiting_for_resource", "preparing" -> JBColor(0xAB6100, 0xC17D10)
        else -> JBColor(0x737278, 0x89888D)
    }

    fun thread(t: CrThread): Icon = when (t.source) {
        ThreadSource.SELF -> AllIcons.General.InspectionsEye
        ThreadSource.PIPELINE -> AllIcons.RunConfigurations.TestFailed
        ThreadSource.GITLAB -> when {
            t.resolved -> AllIcons.RunConfigurations.TestPassed
            t.suggestion != null -> AllIcons.Actions.IntentionBulb
            else -> AllIcons.General.Balloon
        }
    }
}
