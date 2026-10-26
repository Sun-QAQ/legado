package io.legado.app.ui.about

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import io.legado.app.R
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.utils.putInt
import io.legado.app.utils.startActivity
import kotlinx.coroutines.launch
import java.time.LocalDate

internal object AnnualReadReportPrompt {
    fun showIfNeeded(
        activity: AppCompatActivity,
        dateProvider: () -> LocalDate = { LocalDate.now() },
    ) {
        // onPostResume can run before the lifecycle dispatches RESUMED. Wait instead
        // of dropping the invitation; a background activity also waits until visible.
        activity.lifecycleScope.launch {
            activity.lifecycle.withResumed {
                showInvitation(activity, dateProvider())
            }
        }
    }

    private fun showInvitation(activity: AppCompatActivity, today: LocalDate) {
        if (!LocalConfig.privacyPolicyOk) return
        if (!AnnualReadReportPolicy.shouldPrompt(today,
                LocalConfig.getInt("annualReadReportPromptYear", 0),
                AnnualReadReportStore(activity).hasReport(today.year))) return
        // Mark the invitation, including dismissal, so every app opening does not interrupt reading.
        LocalConfig.putInt("annualReadReportPromptYear", today.year)
        activity.alert(activity.getString(R.string.annual_report_title),
            activity.getString(R.string.annual_report_prompt, today.year)) {
            positiveButton(R.string.annual_report_generate) {
                activity.startActivity<AnnualReadReportActivity> { putExtra("year", today.year) }
            }
            negativeButton(R.string.annual_report_later)
        }
    }
}
