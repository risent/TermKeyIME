package com.termkey.ime

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import kotlin.math.max
import kotlin.math.min

private data class LexiconEntry(
    val text: String,
    val baseFreq: Int,
    val syllableCount: Int,
    val userScore: Int,
)

private data class CandidatePath(
    val text: String,
    val score: Int,
    val segmentCount: Int,
)

/**
 * Offline lexicon store backed by a prebuilt SQLite asset seeded from a curated
 * subset of rime_wanxiang. The asset is copied once; user frequency updates are
 * stored in the same database via the `user_freq` table.
 */
class ChineseLexiconStore(
    context: Context,
) {
    companion object {
        private const val TAG = "TermKeyLexicon"
        private const val DB_NAME = "chinese_lexicon.db"
        private const val USER_SCORE_INCREMENT = 240
        private const val MAX_SPAN_SYLLABLES = 6
        private const val PATH_BEAM_WIDTH = 16
        private const val JOIN_PENALTY = 32
        private const val EXACT_QUERY_LIMIT = 9
        private const val SINGLE_SYLLABLE_LIMIT = 32
        private const val SPAN_QUERY_LIMIT = 6
        private const val CACHE_SIZE = 256
    }

    private val appContext = context.applicationContext
    private val database: SQLiteDatabase? by lazy {
        runCatching { openDatabase() }
            .onFailure { Log.e(TAG, "Failed to open Chinese lexicon database", it) }
            .getOrNull()
    }
    private val queryCache = object : LinkedHashMap<String, List<LexiconEntry>>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<LexiconEntry>>?): Boolean {
            return size > CACHE_SIZE
        }
    }

    fun lookupCandidates(syllables: List<String>, limit: Int = EXACT_QUERY_LIMIT): List<String> {
        if (syllables.isEmpty()) return emptyList()
        val resolvedLimit = if (syllables.size == 1) max(limit, SINGLE_SYLLABLE_LIMIT) else limit
        val fullKey = syllables.joinToString("'")
        val exactMatches = queryEntries(fullKey, resolvedLimit)
        val bestPaths = buildBestPaths(syllables)

        val scored = linkedMapOf<String, Int>()
        exactMatches.forEach { entry ->
            val score = scoreEntry(entry, isWholeSpan = true)
            scored[entry.text] = max(scored[entry.text] ?: Int.MIN_VALUE, score)
        }
        bestPaths.forEach { path ->
            scored[path.text] = max(scored[path.text] ?: Int.MIN_VALUE, path.score)
        }

        return scored.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenByDescending { it.key.length }
                    .thenBy { it.key },
            )
            .map { it.key }
            .take(resolvedLimit)
    }

    fun recordSelection(pinyinKey: String, text: String) {
        val db = database ?: return
        if (pinyinKey.isBlank() || text.isBlank()) return
        val syllableCount = pinyinKey.count { it == '\'' } + 1
        runCatching {
            db.execSQL(
                """
                INSERT INTO user_freq(pinyin_key, text, syllable_count, score, updated_at)
                VALUES (?, ?, ?, ?, strftime('%s', 'now'))
                ON CONFLICT(pinyin_key, text) DO UPDATE SET
                    syllable_count = excluded.syllable_count,
                    score = user_freq.score + excluded.score,
                    updated_at = excluded.updated_at
                """.trimIndent(),
                arrayOf(pinyinKey, text, syllableCount, USER_SCORE_INCREMENT),
            )
        }.onFailure {
            Log.e(TAG, "Failed to persist user frequency for $pinyinKey -> $text", it)
        }
        invalidateCacheFor(pinyinKey)
    }

    private fun buildBestPaths(syllables: List<String>): List<CandidatePath> {
        val pathCache = Array(syllables.size + 1) { emptyList<CandidatePath>() }
        pathCache[syllables.size] = listOf(CandidatePath("", 0, 0))

        for (start in syllables.lastIndex downTo 0) {
            val candidates = mutableListOf<CandidatePath>()
            val maxEnd = min(syllables.size, start + MAX_SPAN_SYLLABLES)
            for (end in (start + 1)..maxEnd) {
                val key = syllables.subList(start, end).joinToString("'")
                val spanEntries = queryEntries(key, SPAN_QUERY_LIMIT)
                if (spanEntries.isEmpty()) continue

                val endsSentence = end == syllables.size
                if (endsSentence) {
                    spanEntries.forEach { entry ->
                        candidates += CandidatePath(
                            text = entry.text,
                            score = scoreEntry(entry, isWholeSpan = start == 0),
                            segmentCount = 1,
                        )
                    }
                    continue
                }

                val suffixes = pathCache[end]
                if (suffixes.isEmpty()) continue
                spanEntries.forEach { entry ->
                    val entryScore = scoreEntry(entry, isWholeSpan = false)
                    suffixes.take(SPAN_QUERY_LIMIT).forEach { suffix ->
                        candidates += CandidatePath(
                            text = entry.text + suffix.text,
                            score = entryScore + suffix.score - JOIN_PENALTY,
                            segmentCount = suffix.segmentCount + 1,
                        )
                    }
                }
            }

            pathCache[start] = candidates
                .groupBy { it.text }
                .values
                .mapNotNull { sameText -> sameText.maxByOrNull { it.score } }
                .sortedWith(
                    compareByDescending<CandidatePath> { it.score }
                        .thenBy { it.segmentCount }
                        .thenByDescending { it.text.length },
                )
                .take(PATH_BEAM_WIDTH)
        }

        return pathCache.first()
    }

    private fun scoreEntry(entry: LexiconEntry, isWholeSpan: Boolean): Int {
        val lexicalScore = entry.baseFreq + entry.userScore
        val structureBonus = entry.syllableCount * 72 + entry.text.length * 12
        val exactBonus = if (isWholeSpan) 96 else 0
        return lexicalScore + structureBonus + exactBonus
    }

    private fun queryEntries(pinyinKey: String, limit: Int): List<LexiconEntry> {
        val db = database ?: return emptyList()
        val cacheKey = "$limit|$pinyinKey"
        synchronized(queryCache) {
            queryCache[cacheKey]?.let { return it }
        }

        val rows = mutableListOf<LexiconEntry>()
        db.rawQuery(
            """
            SELECT text, base_freq, syllable_count, user_score
            FROM (
                SELECT
                    l.text AS text,
                    l.freq AS base_freq,
                    l.syllable_count AS syllable_count,
                    COALESCE(u.score, 0) AS user_score
                FROM lexicon l
                LEFT JOIN user_freq u
                    ON u.pinyin_key = l.pinyin_key
                   AND u.text = l.text
                WHERE l.pinyin_key = ?

                UNION ALL

                SELECT
                    u.text AS text,
                    0 AS base_freq,
                    u.syllable_count AS syllable_count,
                    u.score AS user_score
                FROM user_freq u
                WHERE u.pinyin_key = ?
                  AND NOT EXISTS (
                    SELECT 1
                    FROM lexicon l
                    WHERE l.pinyin_key = u.pinyin_key
                      AND l.text = u.text
                  )
            )
            ORDER BY (base_freq + user_score) DESC, syllable_count DESC, length(text) DESC, text ASC
            LIMIT ?
            """.trimIndent(),
            arrayOf(pinyinKey, pinyinKey, limit.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += LexiconEntry(
                    text = cursor.getString(0),
                    baseFreq = cursor.getInt(1),
                    syllableCount = cursor.getInt(2),
                    userScore = cursor.getInt(3),
                )
            }
        }

        synchronized(queryCache) {
            queryCache[cacheKey] = rows
        }
        return rows
    }

    private fun invalidateCacheFor(pinyinKey: String) {
        synchronized(queryCache) {
            val iterator = queryCache.keys.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().endsWith("|$pinyinKey")) {
                    iterator.remove()
                }
            }
        }
    }

    private fun openDatabase(): SQLiteDatabase {
        val dbFile = appContext.getDatabasePath(DB_NAME)
        if (!dbFile.exists()) {
            copyAssetDatabase(dbFile)
        }
        return SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).also { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS user_freq (
                    pinyin_key TEXT NOT NULL,
                    text TEXT NOT NULL,
                    syllable_count INTEGER NOT NULL,
                    score INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (pinyin_key, text)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_user_freq_pinyin ON user_freq(pinyin_key)")
        }
    }

    private fun copyAssetDatabase(target: File) {
        target.parentFile?.mkdirs()
        appContext.assets.open(DB_NAME).use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }
}
