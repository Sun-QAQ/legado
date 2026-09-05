package io.legado.app.ui.main.my

import android.os.Bundle
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivitySettingsBinding
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 全部设置（我的页右上角齿轮进入）
 */
class SettingsActivity : BaseActivity<ActivitySettingsBinding>() {

    override val binding by viewBinding(ActivitySettingsBinding::inflate)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        val fragmentTag = "prefFragment"
        var preferenceFragment = supportFragmentManager.findFragmentByTag(fragmentTag)
        if (preferenceFragment == null) preferenceFragment = MyFragment.MyPreferenceFragment()
        supportFragmentManager.beginTransaction()
            .replace(R.id.pre_fragment, preferenceFragment, fragmentTag)
            .commit()
    }

}