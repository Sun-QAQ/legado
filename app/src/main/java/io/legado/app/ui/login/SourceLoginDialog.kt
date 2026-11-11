package io.legado.app.ui.login

import android.content.DialogInterface
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.core.view.setPadding
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.flexbox.FlexboxLayout
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.gson.JsonParser
import com.script.rhino.runScriptWithContext
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.RowUi
import io.legado.app.databinding.DialogLoginBinding
import io.legado.app.databinding.ItemFilletTextBinding
import io.legado.app.databinding.ItemSourceEditBinding
import io.legado.app.help.source.NgJsSource
import io.legado.app.help.source.SourceLoginScript
import io.legado.app.help.source.SourceLoginUi
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.model.webBook.NgJsRuntime
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.applyTint
import io.legado.app.utils.dpToPx
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.openUrl
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx


class SourceLoginDialog : BaseDialogFragment(R.layout.dialog_login, true) {

    private val binding by viewBinding(DialogLoginBinding::bind)
    private val viewModel by activityViewModels<SourceLoginViewModel>()
    private lateinit var loginScript: SourceLoginScript
    private var loginRows: List<RowUi> = emptyList()
    private var loginBusy = false
    private var loginUiReady = false
    private var ngState: com.google.gson.JsonElement? = null

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val source = viewModel.source ?: return
        if (source is BookSource && NgJsSource.isNg(source)) {
            initNgLogin(source)
            return
        }
        // 工具栏保持透明，让圆角卡片背景透出，避免直角覆盖顶部圆角
        binding.toolBar.title = getString(R.string.login_source, source.getTag())
        loginScript = SourceLoginScript(source)
        binding.toolBar.inflateMenu(R.menu.source_login)
        binding.toolBar.menu.applyTint(requireContext())
        binding.toolBar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_ok -> {
                    if (!loginBusy) login(source, getLoginData(loginRows))
                }

                R.id.menu_show_login_header -> alert {
                    setTitle(R.string.login_header)
                    source.getLoginHeader()?.let { loginHeader ->
                        setMessage(loginHeader)
                        positiveButton(R.string.copy_text) {
                            appCtx.sendToClip(loginHeader)
                        }
                    }
                }

                R.id.menu_del_login_header -> source.removeLoginHeader()
                R.id.menu_log -> showDialogFragment<AppLogDialog>()
            }
            return@setOnMenuItemClickListener true
        }
        refreshLoginUi(source, initialize = true)
    }

    private fun initNgLogin(source: BookSource) {
        binding.toolBar.title = getString(R.string.login_source, source.getTag())
        binding.toolBar.inflateMenu(R.menu.source_login)
        binding.toolBar.menu.applyTint(requireContext())
        binding.toolBar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_ok -> dismiss() // NG 登录状态由脚本管理，关闭时不能清除设备信息。
                R.id.menu_log -> showDialogFragment<AppLogDialog>()
                R.id.menu_del_login_header -> source.removeLoginHeader()
                R.id.menu_show_login_header -> alert {
                    setTitle(R.string.login_header)
                    setMessage(source.getLoginHeader().orEmpty())
                }
            }
            true
        }
        refreshNgLogin(source)
    }

    private fun refreshNgLogin(source: BookSource, action: String? = null, data: Any? = null) {
        binding.flexbox.isEnabled = false
        lifecycleScope.launch {
            runCatching {
                val rows = withContext(IO) {
                    if (action != null) {
                        val result = JsonParser.parseString(
                            NgJsRuntime.call(
                                source,
                                "loginAction",
                                action,
                                data,
                                ngState,
                                bypassRateLimit = true
                            )
                        )
                        if (result.isJsonObject) ngState = result.asJsonObject["state"] ?: ngState
                    }
                    val ui = JsonParser.parseString(NgJsRuntime.call(source, "loginUi", ngState))
                    val rowsJson = if (ui.isJsonArray) ui else ui.asJsonObject["rows"]
                    GSON.fromJsonArray<RowUi>(rowsJson.toString()).getOrThrow()
                }
                binding.flexbox.removeAllViews()
                rows.forEachIndexed { index, row ->
                    if (row.type == RowUi.Type.button) {
                        val item = ItemFilletTextBinding.inflate(layoutInflater, binding.root, false)
                        item.textView.text = row.name
                        item.textView.setPadding(16.dpToPx())
                        applyAccentColor(item.textView)
                        item.root.setOnClickListener {
                            if (binding.flexbox.isEnabled) {
                                refreshNgLogin(source, row.action, getLoginData(rows))
                            }
                        }
                        binding.flexbox.addView(item.root)
                        row.style().apply(item.root)
                    } else if (row.type == RowUi.Type.text || row.type == RowUi.Type.password) {
                        val item = ItemSourceEditBinding.inflate(layoutInflater, binding.root, false)
                        item.root.id = index + 1000
                        item.textInputLayout.hint = row.name
                        if (row.type == RowUi.Type.password) item.editText.inputType =
                            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        binding.flexbox.addView(item.root)
                    }
                }
            }.onFailure {
                ensureActive()
                AppLog.put("NG JS 登录失败", it)
                context?.toastOnUi(it.localizedMessage)
            }
            binding.flexbox.isEnabled = true
        }
    }

    /**
     * 登录按钮底色使用主题设置中的强调色，按下加深，文字颜色按底色明暗自动取黑/白
     */
    private fun applyAccentColor(view: TextView) {
        val accentColor = requireContext().accentColor
        view.background = Selector.shapeBuild()
            .setCornerRadius(999.dpToPx())
            .setDefaultBgColor(accentColor)
            .setPressedBgColor(ColorUtils.darkenColor(accentColor))
            .create()
        view.setTextColor(
            if (ColorUtils.isColorLight(accentColor)) Color.BLACK else Color.WHITE
        )
    }

    private fun setLoginBusy(busy: Boolean) {
        loginBusy = busy
        fun enable(view: View) {
            view.isEnabled = !busy
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) enable(view.getChildAt(index))
            }
        }
        enable(binding.flexbox)
        binding.toolBar.menu.findItem(R.id.menu_ok)?.isEnabled = !busy && loginUiReady
    }

    private fun refreshLoginUi(source: BaseSource, initialize: Boolean = false, action: String? = null) {
        if (loginBusy) return
        if (!initialize) loginScript.data.putAll(getLoginData(loginRows))
        setLoginBusy(true)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val rows = withContext(IO) {
                    runScriptWithContext {
                        if (initialize) loginScript.data.putAll(source.getLoginInfoMap().orEmpty())
                        if (action != null) loginScript.runAction(action)
                        if (action == null || loginScript.reloadRequested) loginScript.readUi() else loginRows
                    }
                }
                renderLoginUi(source, rows)
                loginUiReady = true
            } catch (e: Exception) {
                ensureActive()
                AppLog.put("登录 UI 执行失败", e)
                context?.toastOnUi("登录 UI 执行失败：${e.localizedMessage}")
                if (loginRows.isEmpty()) {
                    binding.flexbox.removeAllViews()
                    val error = TextView(requireContext()).apply {
                        text = "登录 UI 加载失败\n${e.localizedMessage}"
                        setPadding(16.dpToPx())
                    }
                    binding.flexbox.addView(error, FlexboxLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ))
                    val retry = ItemFilletTextBinding.inflate(layoutInflater, binding.flexbox, false)
                    retry.textView.setText(R.string.retry)
                    applyAccentColor(retry.textView)
                    retry.root.setOnClickListener { refreshLoginUi(source, initialize = initialize) }
                    binding.flexbox.addView(retry.root)
                }
            } finally {
                if (view != null) setLoginBusy(false)
            }
        }
    }

    private fun renderLoginUi(source: BaseSource, rows: List<RowUi>) {
        binding.flexbox.removeAllViews()
        loginRows = rows
        rows.forEachIndexed { index, row ->
            val title = row.viewName ?: row.name
            val value = loginScript.data[row.name].orEmpty()
            val view = when (row.type) {
                RowUi.Type.text, RowUi.Type.password -> {
                    val item = ItemSourceEditBinding.inflate(layoutInflater, binding.flexbox, false)
                    item.root.id = index + 1000
                    item.textInputLayout.hint = title
                    if (row.type == RowUi.Type.password) item.editText.inputType =
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    item.editText.setText(value)
                    item.root
                }
                RowUi.Type.button -> {
                    val item = ItemFilletTextBinding.inflate(layoutInflater, binding.flexbox, false)
                    item.textView.text = title
                    item.textView.maxLines = Int.MAX_VALUE
                    item.textView.ellipsize = null
                    item.textView.setPadding(16.dpToPx())
                    applyAccentColor(item.textView)
                    item.root.setOnClickListener { handleButtonClick(source, row) }
                    item.root
                }
                RowUi.Type.select -> {
                    val choices = row.chars.orEmpty()
                    val selected = choices.indexOf(value).coerceAtLeast(0)
                    loginScript.data[row.name] = choices[selected]
                    LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(16.dpToPx(), 8.dpToPx(), 16.dpToPx(), 8.dpToPx())
                        addView(TextView(context).apply { text = title })
                        addView(Spinner(context).apply {
                            id = index + 1000
                            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, choices).apply {
                                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                            }
                            setSelection(selected)
                            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                                    val next = choices[position]
                                    if (loginBusy || loginScript.data[row.name] == next) return
                                    loginScript.data[row.name] = next
                                    handleButtonClick(source, row)
                                }
                            }
                        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    }
                }
                RowUi.Type.toggle -> {
                    val choices = row.chars.orEmpty()
                    SwitchMaterial(requireContext()).apply {
                        isChecked = value == choices[1]
                        loginScript.data[row.name] = choices[if (isChecked) 1 else 0]
                        text = "$title  ${loginScript.data[row.name]}"
                        setPadding(16.dpToPx())
                        setOnCheckedChangeListener { _, checked ->
                            if (!loginBusy) {
                                loginScript.data[row.name] = choices[if (checked) 1 else 0]
                                text = "$title  ${loginScript.data[row.name]}"
                                handleButtonClick(source, row)
                            }
                        }
                    }
                }
                else -> return@forEachIndexed
            }
            if (view.layoutParams !is FlexboxLayout.LayoutParams) {
                view.layoutParams = FlexboxLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            binding.flexbox.addView(view)
            row.style().apply(view)
        }
    }

    private fun handleButtonClick(source: BaseSource, row: RowUi) {
        if (loginBusy) return
        val action = row.action ?: return
        if (action.isAbsUrl()) context?.openUrl(action)
        else refreshLoginUi(source, action = action)
    }

    private fun getLoginData(loginUi: List<RowUi>?): HashMap<String, String> {
        val loginData = if (::loginScript.isInitialized) HashMap(loginScript.data) else hashMapOf<String, String>()
        loginUi?.forEachIndexed { index, rowUi ->
            when (rowUi.type) {
                "text", "password" -> {
                    val rowView = binding.root.findViewById<View>(index + 1000)
                    ItemSourceEditBinding.bind(rowView).editText.text?.let {
                        loginData[rowUi.name] = it.toString()
                    }
                }
            }
        }
        return loginData
    }

    private fun login(source: BaseSource, loginData: HashMap<String, String>) {
        setLoginBusy(true)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(IO) {
                    if (loginData.isEmpty()) {
                        // 动态菜单可能没有输入项，关闭时保留其脚本管理的登录信息。
                        if (SourceLoginUi.script(source.loginUi.orEmpty()) == null) {
                            source.removeLoginInfo()
                        }
                    } else {
                        check(source.putLoginInfo(GSON.toJson(loginData))) { "保存登录信息失败" }
                        runScriptWithContext {
                            source.login {
                                put("java", loginScript)
                                put("result", loginData)
                            }
                        }
                        context?.toastOnUi(R.string.success)
                    }
                }
                dismiss()
            } catch (e: Exception) {
                ensureActive()
                AppLog.put("登录出错", e)
                context?.toastOnUi("登录出错\n${e.localizedMessage}")
            } finally {
                if (view != null) setLoginBusy(false)
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        activity?.finish()
    }

}
