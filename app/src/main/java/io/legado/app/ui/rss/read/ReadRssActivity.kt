package io.legado.app.ui.rss.read

import android.os.Bundle
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityRssReadHostBinding
import io.legado.app.utils.viewbindingdelegate.viewBinding

/** 普通订阅文章仍通过独立页面阅读，与主界面的常驻网页标签共用阅读组件。 */
class ReadRssActivity : BaseActivity<ActivityRssReadHostBinding>() {
    override val binding by viewBinding(ActivityRssReadHostBinding::inflate)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(binding.rssReadContainer.id, ReadRssFragment().apply {
                    arguments = Bundle(intent.extras ?: Bundle())
                })
                .commitNow()
        }
    }
}
