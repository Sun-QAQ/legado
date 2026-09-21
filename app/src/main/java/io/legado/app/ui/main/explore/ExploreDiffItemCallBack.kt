package io.legado.app.ui.main.explore

import androidx.recyclerview.widget.DiffUtil
import io.legado.app.data.entities.BookSourcePart


class ExploreDiffItemCallBack : DiffUtil.ItemCallback<BookSourcePart>() {

    override fun areItemsTheSame(oldItem: BookSourcePart, newItem: BookSourcePart): Boolean {
        return oldItem == newItem
    }

    override fun areContentsTheSame(oldItem: BookSourcePart, newItem: BookSourcePart): Boolean {
        // 名称、分组、登录标记都会展示在卡片上，变化时需要重新绑定
        return oldItem.bookSourceName == newItem.bookSourceName
            && oldItem.bookSourceGroup == newItem.bookSourceGroup
            && oldItem.hasLoginUrl == newItem.hasLoginUrl
    }

}