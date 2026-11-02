package io.legado.app.ui.config

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.help.voice.VoiceAiException
import io.legado.app.help.voice.VoiceAiInterpreter
import io.legado.app.help.voice.VoiceAssistant
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.ui.book.agent.AiSourceManageActivity
import io.legado.app.utils.getPrefLong
import io.legado.app.utils.putPrefLong
import io.legado.app.utils.putPrefString
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx

/** Voice preferences retain their existing keys and shared preference file. */
class VoiceAssistantConfigFragment : PreferenceFragment() {

    private val voicePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        findPreference<SwitchPreferenceCompat>(VoiceAssistant.ENABLED)?.isChecked = granted
        if (!granted) appCtx.toastOnUi(R.string.voice_assistant_permission)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_config_voice_assistant)
        findPreference<SwitchPreferenceCompat>(VoiceAssistant.ENABLED)?.setOnPreferenceChangeListener { _, value ->
            if (value == true && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED) {
                voicePermission.launch(Manifest.permission.RECORD_AUDIO)
                false
            } else true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.voice_assistant_settings)
    }

    override fun onResume() {
        super.onResume()
        updateVoiceAiSummary()
    }

    private fun updateVoiceAiSummary() {
        viewLifecycleOwner.lifecycleScope.launch {
            val source = try { VoiceAiInterpreter.source() } catch (_: VoiceAiException) { null }
            val modelSummary = if (source == null) {
                getString(R.string.voice_assistant_ai_configure)
            } else {
                val prefix = if (requireContext().getPrefLong(VoiceAiInterpreter.SOURCE_ID) == 0L)
                    getString(R.string.voice_assistant_ai_follow) + " · " else ""
                "$prefix${source.name} · ${source.model}"
            }
            findPreference<Preference>(VoiceAiInterpreter.MODEL)?.summary =
                "$modelSummary\n${getString(R.string.voice_assistant_ai_summary)}"
        }
    }

    private fun selectVoiceAiModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            val sources = withContext(Dispatchers.IO) { appDb.aiSourceDao.allEnabled }
            val labels = listOf(getString(R.string.voice_assistant_ai_follow)) + sources.map { it.name } +
                getString(R.string.ai_source_manage)
            requireContext().selector(getString(R.string.voice_assistant_ai_model), labels) { _, index ->
                when (index) {
                    0 -> {
                        putPrefLong(VoiceAiInterpreter.SOURCE_ID, 0)
                        putPrefString(VoiceAiInterpreter.MODEL, "")
                        updateVoiceAiSummary()
                    }
                    labels.lastIndex -> startActivity<AiSourceManageActivity>()
                    else -> {
                        val source = sources[index - 1]
                        val models = (listOf(source.model) + source.getModelList()).filter { it.isNotBlank() }.distinct()
                        if (models.isEmpty()) {
                            appCtx.toastOnUi(R.string.voice_assistant_ai_configure)
                        } else requireContext().selector(source.name, models) { _, selected ->
                            putPrefLong(VoiceAiInterpreter.SOURCE_ID, source.id)
                            putPrefString(VoiceAiInterpreter.MODEL, models[selected])
                            updateVoiceAiSummary()
                        }
                    }
                }
            }
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            "voiceAssistantListen" -> { VoiceAssistant.listenNow(); return true }
            VoiceAiInterpreter.MODEL -> { selectVoiceAiModel(); return true }
        }
        return super.onPreferenceTreeClick(preference)
    }
}
