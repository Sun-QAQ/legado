package io.legado.app.ui.about

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import io.legado.app.R
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.utils.putInt
import io.legado.app.utils.startActivity
import java.time.LocalDate

internal object AnnualReadReportPrompt {
    fun showIfNeeded(activity: AppCompatActivity, today: LocalDate = LocalDate.now()) {
        if (!LocalConfig.privacyPolicyOk ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
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
