package io.legado.app.ui.main.my

import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.preference.Preference
import androidx.lifecycle.lifecycleScope
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.base.BaseFragment
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.databinding.FragmentMyConfigBinding
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.glide.ImageLoader
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.prefs.NameListPreference
import io.legado.app.lib.prefs.SwitchPreference
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.service.WebService
import io.legado.app.ui.about.AboutActivity
import io.legado.app.ui.about.ReadRecordActivity
import io.legado.app.ui.book.bookmark.AllBookmarkActivity
import io.legado.app.ui.book.agent.AiSourceManageActivity
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.book.toc.rule.TxtTocRuleActivity
import io.legado.app.ui.config.ConfigActivity
import io.legado.app.ui.config.ConfigTag
import io.legado.app.ui.dict.rule.DictRuleActivity
import io.legado.app.ui.file.FileManageActivity
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.replace.ReplaceRuleActivity
import io.legado.app.utils.LogUtils
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.SelectImageContract
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.launch
import io.legado.app.utils.observeEventSticky
import io.legado.app.utils.openUrl
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefString
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setAccentCursor
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 我的界面：个人资料 + 统计 + 关键设置快捷键，全部设置移入右上角齿轮
 */
class MyFragment() : BaseFragment(R.layout.fragment_my_config), MainFragmentInterface {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    override val position: Int? get() = arguments?.getInt("position")

    private val binding by viewBinding(FragmentMyConfigBinding::bind)

    private val avatarImagePicker = registerForActivityResult(SelectImageContract()) { result ->
        result.uri ?: return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            val path = withContext(Dispatchers.IO) { copyAvatarImage(result.uri) }
            if (path != null) {
                requireContext().putPrefString(PreferKey.userAvatarImage, path)
                showAvatarImage(path)
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setSupportToolbar(binding.titleBar.toolbar)
        initProfile()
        initStats()
        initShortcuts()
        initWebService()
    }

    private fun initProfile() {
        val nickname = requireContext().getPrefString(PreferKey.userNickname)
            ?: getString(R.string.user_nickname_default)
        val avatar = requireContext().getPrefString(PreferKey.userAvatar) ?: "🙂"
        val avatarImage = requireContext().getPrefString(PreferKey.userAvatarImage)
        binding.tvNickname.text = nickname
        binding.tvVersion.text = "v${BuildConfig.VERSION_NAME}"
        val accent = requireContext().accentColor
        binding.tvReadingTime.setTextColor(accent)
        binding.tvFinished.setTextColor(accent)
        binding.tvShelf.setTextColor(accent)
        binding.ivBookSourceIcon.imageTintList = ColorStateList.valueOf(accent)
        binding.ivReadRecordIcon.imageTintList = ColorStateList.valueOf(accent)
        updateProfileCard(accent)
        if (avatarImage != null && File(avatarImage).exists()) {
            showAvatarImage(avatarImage)
        } else {
            binding.tvAvatar.text = avatar
            binding.ivAvatar.gone()
            binding.tvAvatar.visible()
        }
        binding.tvNickname.setOnClickListener { editNickname() }
        binding.tvAvatar.setOnClickListener { chooseAvatar() }
        binding.ivAvatar.setOnClickListener { chooseAvatar() }
    }

    private fun updateProfileCard(accent: Int) {
        val lighter = ColorUtils.blendColors(accent, Color.WHITE, 0.3f)
        val gradient = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(accent, lighter)
        ).apply {
            cornerRadius = 20.dpToPx().toFloat()
        }
        binding.profileCard.background = gradient
    }

    private fun chooseAvatar() {
        val options = arrayListOf(
            getString(R.string.my_avatar_local),
            getString(R.string.my_avatar_emoji),
            getString(R.string.my_avatar_reset)
        )
        requireContext().selector(getString(R.string.my_avatar), options) { _, index ->
            when (index) {
                0 -> avatarImagePicker.launch()
                1 -> chooseAvatarEmoji()
                2 -> resetAvatar()
            }
        }
    }

    private fun chooseAvatarEmoji() {
        val avatars = arrayListOf("🙂", "😄", "🤓", "😎", "🥳", "📚", "🌟", "🐱", "🦊", "🌙")
        requireContext().selector(getString(R.string.my_avatar), avatars) { _, index ->
            requireContext().putPrefString(PreferKey.userAvatar, avatars[index])
            requireContext().putPrefString(PreferKey.userAvatarImage, null)
            binding.tvAvatar.text = avatars[index]
            binding.ivAvatar.gone()
            binding.tvAvatar.visible()
        }
    }

    private fun resetAvatar() {
        requireContext().putPrefString(PreferKey.userAvatar, null)
        requireContext().putPrefString(PreferKey.userAvatarImage, null)
        binding.tvAvatar.text = "🙂"
        binding.ivAvatar.gone()
        binding.tvAvatar.visible()
    }

    private fun showAvatarImage(path: String) {
        binding.tvAvatar.gone()
        binding.ivAvatar.visible()
        ImageLoader.load(requireContext(), File(path)).circleCrop().into(binding.ivAvatar)
    }

    private fun copyAvatarImage(uri: Uri): String? = runCatching {
        val file = File(requireContext().filesDir, "avatar")
        requireContext().contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        file.absolutePath
    }.getOrNull()

    private fun editNickname() {
        val context = requireContext()
        alert(getString(R.string.my_edit_nickname)) {
            val editTextBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = getString(R.string.my_nickname_hint)
                editView.setText(context.getPrefString(PreferKey.userNickname) ?: "")
                editView.setAccentCursor()
                // 清除 AutoCompleteTextView 自带的强调色背景着色, 用强调色边框包裹输入框
                editView.supportBackgroundTintList = null
                editView.backgroundTintList = null
                val inputBg = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = 14f.dpToPx()
                    setStroke(1.dpToPx(), context.accentColor)
                }
                editView.background = inputBg
            }
            customView {
                editTextBinding.root
            }
            okButton {
                val name = editTextBinding.editView.text.toString().trim()
                if (name.isNotEmpty()) {
                    context.putPrefString(PreferKey.userNickname, name)
                    binding.tvNickname.text = name
                }
            }
            cancelButton()
        }
    }

    private fun initStats() {
        viewLifecycleOwner.lifecycleScope.launch {
            val readingTime = withContext(Dispatchers.IO) { appDb.readRecordDao.allTime }
            val finished = withContext(Dispatchers.IO) { appDb.bookDao.countFinished() }
            val shelf = withContext(Dispatchers.IO) { appDb.bookDao.countAll() }
            binding.tvReadingTime.text = "${(readingTime / 3_600_000f).toInt()}H"
            binding.tvFinished.text = finished.toString()
            binding.tvShelf.text = shelf.toString()
        }
    }

    private fun initShortcuts() {
        binding.llBookSource.setOnClickListener { startActivity<BookSourceActivity>() }
        binding.llAiSource.setOnClickListener { startActivity<AiSourceManageActivity>() }
        binding.llTheme.setOnClickListener {
            startActivity<ConfigActivity> {
                putExtra("configTag", ConfigTag.THEME_CONFIG)
            }
        }
        binding.llBackup.setOnClickListener {
            startActivity<ConfigActivity> {
                putExtra("configTag", ConfigTag.BACKUP_CONFIG)
            }
        }
        binding.llReplace.setOnClickListener { startActivity<ReplaceRuleActivity>() }
        binding.llReadRecord.setOnClickListener { startActivity<ReadRecordActivity>() }
    }

    /**
     * Web 服务开关，与"全部设置"中的 webService 共用同一个偏好项
     */
    private fun initWebService() {
        binding.swWebService.isChecked = WebService.isRun
        updateWebServiceSummary()
        binding.ivWebServiceIcon.imageTintList = ColorStateList.valueOf(requireContext().accentColor)
        binding.llWebService.setOnClickListener {
            val enable = !binding.swWebService.isChecked
            binding.swWebService.isChecked = enable
            requireContext().putPrefBoolean(PreferKey.webService, enable)
            if (enable) {
                WebService.start(requireContext())
            } else {
                WebService.stop(requireContext())
            }
        }
        observeEventSticky<String>(EventBus.WEB_SERVICE) {
            binding.swWebService.isChecked = WebService.isRun
            updateWebServiceSummary()
        }
    }

    private fun updateWebServiceSummary() {
        binding.tvWebServiceSummary.text = if (WebService.isRun) {
            WebService.hostAddress
        } else {
            getString(R.string.web_service_desc)
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu) {
        menuInflater.inflate(R.menu.main_my, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem) {
        when (item.itemId) {
            R.id.menu_settings -> startActivity<SettingsActivity>()
            R.id.menu_help -> showHelp("appHelp")
        }
    }

    /**
     * 全部设置（由 SettingsActivity 承载）
     */
    class MyPreferenceFragment : PreferenceFragment(),
        SharedPreferences.OnSharedPreferenceChangeListener {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            putPrefBoolean(PreferKey.webService, WebService.isRun)
            addPreferencesFromResource(R.xml.pref_main)
            findPreference<SwitchPreference>("webService")?.onLongClick {
                if (!WebService.isRun) {
                    return@onLongClick false
                }
                context?.selector(arrayListOf("复制地址", "浏览器打开")) { _, i ->
                    when (i) {
                        0 -> context?.sendToClip(it.summary.toString())
                        1 -> context?.openUrl(it.summary.toString())
                    }
                }
                true
            }
            observeEventSticky<String>(EventBus.WEB_SERVICE) {
                findPreference<SwitchPreference>(PreferKey.webService)?.let {
                    it.isChecked = WebService.isRun
                    it.summary = if (WebService.isRun) {
                        WebService.hostAddress
                    } else {
                        getString(R.string.web_service_desc)
                    }
                }
            }
            findPreference<NameListPreference>(PreferKey.themeMode)?.let {
                it.setOnPreferenceChangeListener { _, _ ->
                    view?.post { ThemeConfig.applyDayNight(requireContext()) }
                    true
                }
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            listView.setEdgeEffectColor(primaryColor)
        }

        override fun onResume() {
            super.onResume()
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onPause() {
            preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
            super.onPause()
        }

        override fun onSharedPreferenceChanged(
            sharedPreferences: SharedPreferences?,
            key: String?
        ) {
            when (key) {
                PreferKey.webService -> {
                    if (requireContext().getPrefBoolean("webService")) {
                        WebService.start(requireContext())
                    } else {
                        WebService.stop(requireContext())
                    }
                }

                "recordLog" -> LogUtils.upLevel()
            }
        }

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            when (preference.key) {
                "bookSourceManage" -> startActivity<BookSourceActivity>()
                "aiSourceManage" -> startActivity<AiSourceManageActivity>()
                "replaceManage" -> startActivity<ReplaceRuleActivity>()
                "dictRuleManage" -> startActivity<DictRuleActivity>()
                "txtTocRuleManage" -> startActivity<TxtTocRuleActivity>()
                "bookmark" -> startActivity<AllBookmarkActivity>()
                "setting" -> startActivity<ConfigActivity> {
                    putExtra("configTag", ConfigTag.OTHER_CONFIG)
                }

                "web_dav_setting" -> startActivity<ConfigActivity> {
                    putExtra("configTag", ConfigTag.BACKUP_CONFIG)
                }

                "theme_setting" -> startActivity<ConfigActivity> {
                    putExtra("configTag", ConfigTag.THEME_CONFIG)
                }

                "fileManage" -> startActivity<FileManageActivity>()
                "readRecord" -> startActivity<ReadRecordActivity>()
                "about" -> startActivity<AboutActivity>()
                "exit" -> activity?.finish()
            }
            return super.onPreferenceTreeClick(preference)
        }
    }
}