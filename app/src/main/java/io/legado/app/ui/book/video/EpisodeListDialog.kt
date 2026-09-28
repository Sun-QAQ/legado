package io.legado.app.ui.book.video

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import io.legado.app.data.appDb
import io.legado.app.databinding.DialogEpisodeListBinding
import io.legado.app.model.VideoPlay
import io.legado.app.ui.book.info.EpisodeAdapter
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 剧集选择
 */
class EpisodeListDialog : BottomSheetDialogFragment() {

    private var _binding: DialogEpisodeListBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogEpisodeListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val adapter = EpisodeAdapter(
            context = requireContext(),
            durChapterIndex = { VideoPlay.durChapterIndex },
            onEpisodeClick = { chapter ->
                VideoPlay.skipTo(chapter.index)
                dismiss()
            }
        )
        binding.rvEpisodes.layoutManager = GridLayoutManager(requireContext(), 4)
        binding.rvEpisodes.adapter = adapter
        val bookUrl = VideoPlay.book?.bookUrl ?: return
        lifecycleScope.launch(IO) {
            val list = appDb.bookChapterDao.getChapterList(bookUrl)
            withContext(Main) {
                adapter.setItems(list)
                if (VideoPlay.durChapterIndex in list.indices) {
                    binding.rvEpisodes.scrollToPosition(VideoPlay.durChapterIndex)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

}
