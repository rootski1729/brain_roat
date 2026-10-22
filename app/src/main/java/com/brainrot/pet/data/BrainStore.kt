package com.brainrot.pet.data

import android.content.Context
import android.util.AtomicFile
import com.brainrot.core.AppData
import com.brainrot.core.AppDataCodec
import com.brainrot.core.DayKey
import com.brainrot.pet.BrainLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Single source of truth for everything persisted. State lives in memory as a [StateFlow];
 * writes are coalesced (at most one per [SAVE_DEBOUNCE_MS]) and go through [AtomicFile], so a crash
 * mid-write leaves the previous file intact. The file is a few KB, small enough to read at startup.
 */
class BrainStore(context: Context, private val scope: CoroutineScope) {
    private val file = AtomicFile(File(context.filesDir, FILE_NAME))
    private val corruptDir = context.filesDir
    private val writeLock = Mutex()
    private val saveRequests = Channel<Unit>(Channel.CONFLATED)

    private val _data = MutableStateFlow(load())
    val data: StateFlow<AppData> = _data.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            for (request in saveRequests) {
                write()
                delay(SAVE_DEBOUNCE_MS)
            }
        }
    }

    /**
     * Applies [transform] atomically. It may run more than once under contention, so it must be a
     * pure function of its input. The day rolls over here, so every writer sees the current day.
     */
    fun update(transform: (AppData) -> AppData) {
        val today = DayKey.of(System.currentTimeMillis())
        _data.update { transform(it.rolledTo(today)) }
        saveRequests.trySend(Unit)
    }

    /** Writes immediately; use after user actions that must survive a process kill. */
    suspend fun flush() = withContext(Dispatchers.IO) { write() }

    private suspend fun write() = writeLock.withLock {
        val snapshot = _data.value
        val stream = try {
            file.startWrite()
        } catch (e: Exception) {
            BrainLog.e("store_write_failed", e, "stage" to "open")
            return@withLock
        }
        try {
            stream.write(AppDataCodec.encode(snapshot).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            BrainLog.e("store_write_failed", e, "stage" to "write")
        }
    }

    private fun load(): AppData {
        val today = DayKey.of(System.currentTimeMillis())
        if (!file.baseFile.exists()) return AppData().rolledTo(today)
        return try {
            AppDataCodec.decode(String(file.readFully(), Charsets.UTF_8)).rolledTo(today)
        } catch (e: Exception) {
            // Keep the bad file for debugging instead of silently overwriting the user's history.
            val backup = File(corruptDir, "$FILE_NAME.corrupt-${System.currentTimeMillis()}")
            val kept = runCatching { file.baseFile.copyTo(backup, overwrite = true) }.isSuccess
            BrainLog.e("store_load_failed", e, "backup_kept" to kept)
            AppData().rolledTo(today)
        }
    }

    companion object {
        private const val FILE_NAME = "brain_state.json"
        private const val SAVE_DEBOUNCE_MS = 1_000L
    }
}
