package io.legado.app.ui.main.aichat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class AIChatViewModel : ViewModel() {

    // 事件通道（根据实际需求添加）
    private val _eventChannel = Channel<AIChatEvent>()
    val eventFlow = _eventChannel.receiveAsFlow()

    // 示例业务逻辑
    fun handleSomeAction() {
        viewModelScope.launch {
            _eventChannel.send(AIChatEvent.SomeEvent)
        }
    }

    // 事件密封类
    sealed class AIChatEvent {
        object SomeEvent : AIChatEvent()
    }
}
