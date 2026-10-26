package io.legado.app.model

import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.ReadSession

internal object ReadSessionStorage {
    fun append(db: AppDatabase, increments: List<ReadSessionTracker.Increment>) {
        db.runInTransaction {
            increments.forEach { (session, _) ->
                val previous = db.readSessionDao.get(session.id)
                val time = session.readTime - (previous?.readTime ?: 0L)
                if (time <= 0) return@forEach
                db.readSessionDao.insert(session)
                db.readRecordDao.addTime(session.deviceId, session.bookName, time, session.endTime)
                db.readStatDao.addTime(session.bookName, session.date, time)
            }
        }
    }

    fun delete(db: AppDatabase, id: String) {
        db.runInTransaction {
            db.readSessionDao.get(id)?.let {
                db.readSessionDao.delete(id)
                db.readStatDao.subtractTime(it.bookName, it.date, it.readTime)
                db.readRecordDao.subtractTime(it.deviceId, it.bookName, it.readTime)
            }
        }
    }

    fun restore(db: AppDatabase, sessions: List<ReadSession>) {
        db.runInTransaction {
            sessions.filter { it.id.isNotBlank() && it.endTime > it.startTime &&
                it.readTime == it.endTime - it.startTime }.forEach {
                val previous = db.readSessionDao.get(it.id)
                if (previous == null || it.readTime > previous.readTime) db.readSessionDao.insert(it)
            }
        }
    }
}
