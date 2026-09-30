package io.legado.app.data.entities

data class ReadRecordShow(
    var bookName: String,
    var readTime: Long,
    var lastRead: Long,
    var coverUrl: String? = null,
    var author: String? = null,
    var origin: String? = null,
)
