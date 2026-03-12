package com.termkey.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalShuangpinEngineTest {

    @Test
    fun wSeriesCodesDecodeToCandidates() {
        val source = FakeLexiconDataSource().apply {
            candidateMap["wang"] = listOf(candidate("网", "wang", 2, 1, 420))
            candidateMap["wan"] = listOf(candidate("完", "wan", 2, 1, 410))
            candidateMap["wei"] = listOf(candidate("为", "wei", 2, 1, 400))
        }
        val engine = NaturalShuangpinEngine(source)

        val whState = engine.append('w').let { engine.append('h') }
        assertEquals("wang", whState.decodedPinyin)
        assertEquals("网", whState.primaryCandidate?.text)

        engine.clear()
        val wjState = engine.append('w').let { engine.append('j') }
        assertEquals("wan", wjState.decodedPinyin)
        assertEquals("完", wjState.primaryCandidate?.text)

        engine.clear()
        val wzState = engine.append('w').let { engine.append('z') }
        assertEquals("wei", wzState.decodedPinyin)
        assertEquals("为", wzState.primaryCandidate?.text)
    }

    @Test
    fun consumingPrefixCandidateKeepsRemainingRawCode() {
        val source = FakeLexiconDataSource().apply {
            candidateMap["wei'wan'wang"] = listOf(
                candidate("全文", "wei'wan'wang", 6, 3, 600, ChineseCandidateKind.SENTENCE),
                candidate("前缀", "wei'wan", 4, 2, 550, ChineseCandidateKind.PHRASE),
            )
            candidateMap["wang"] = listOf(candidate("网", "wang", 2, 1, 420))
        }
        val engine = NaturalShuangpinEngine(source)

        engine.append('w')
        engine.append('z')
        engine.append('w')
        engine.append('j')
        engine.append('w')
        val state = engine.append('h')

        val prefixCandidate = state.candidates.first { it.text == "前缀" }
        engine.consumeCandidate(prefixCandidate)
        val nextState = engine.currentState()

        assertEquals("wh", nextState.rawCode)
        assertEquals("wang", nextState.decodedPinyin)
        assertEquals("网", nextState.primaryCandidate?.text)
    }

    @Test
    fun contextChangesCandidateOrderAndSelectionLearningUsesContext() {
        val source = FakeLexiconDataSource().apply {
            candidateMap["wei"] = listOf(
                candidate("为", "wei", 2, 1, 300),
                candidate("位", "wei", 2, 1, 290),
            )
            contextualOverrides["我们|wei"] = listOf(
                candidate("位", "wei", 2, 1, 480),
                candidate("为", "wei", 2, 1, 300),
            )
        }
        val engine = NaturalShuangpinEngine(source)

        val plainState = engine.append('w').let { engine.append('z') }
        assertEquals("为", plainState.primaryCandidate?.text)
        engine.clear()

        val contextualState = engine.append('w', "我们").let { engine.append('z', "我们") }
        assertEquals("位", contextualState.primaryCandidate?.text)
        assertEquals("我们", source.lastLookupContext)

        val chosen = contextualState.primaryCandidate
        assertNotNull(chosen)
        engine.recordSelection(contextualState, chosen!!, "我们")
        assertEquals("wei", source.lastRecordedPinyinKey)
        assertEquals("位", source.lastRecordedText)
        assertEquals("我们", source.lastRecordedContext)
    }

    @Test
    fun fallbackPhraseBeatsSingleCharacterWhenLexiconResultIsIncomplete() {
        val source = FakeLexiconDataSource().apply {
            candidateMap["wo'men"] = listOf(
                candidate("我", "wo", 2, 1, 950, ChineseCandidateKind.FALLBACK),
            )
        }
        val engine = NaturalShuangpinEngine(source)

        engine.append('w')
        engine.append('o')
        engine.append('m')
        val state = engine.append('f')

        assertEquals("wo'men", state.decodedPinyin)
        assertEquals("我们", state.primaryCandidate?.text)
        assertTrue(state.candidates.any { it.text == "我们" })
    }

    @Test
    fun candidatesPreferShorterPrefixesAfterTopSentence() {
        val source = FakeLexiconDataSource().apply {
            candidateMap["wo'men'xian'zai'hai"] = listOf(
                candidate("我们现在还", "wo'men'xian'zai'hai", 10, 5, 1200, ChineseCandidateKind.SENTENCE),
                candidate("我闷现在还", "wo'men'xian'zai'hai", 10, 5, 1190, ChineseCandidateKind.SENTENCE),
                candidate("我们现在", "wo'men'xian'zai", 8, 4, 1180, ChineseCandidateKind.PHRASE),
                candidate("我们现", "wo'men'xian", 6, 3, 1170, ChineseCandidateKind.PHRASE),
                candidate("我们", "wo'men", 4, 2, 1160, ChineseCandidateKind.PHRASE),
            )
        }
        val engine = NaturalShuangpinEngine(source)

        "womfxmzlhl".forEach { engine.append(it) }
        val state = engine.currentState()

        assertEquals(
            listOf("我们现在还", "我们现在", "我们现", "我们"),
            state.candidates.take(4).map { it.text },
        )
        assertEquals(listOf(5, 4, 3, 2), state.candidates.take(4).map { it.consumedSyllables })
    }

    @Test
    fun spaceCommitOnlyWhenCandidateConsumesWholeCode() {
        val source = FakeLexiconDataSource().apply {
            candidateMap["wei'wan'wang"] = listOf(
                candidate("前缀", "wei'wan", 4, 2, 550, ChineseCandidateKind.PHRASE),
            )
        }
        val engine = NaturalShuangpinEngine(source)

        engine.append('w')
        engine.append('z')
        engine.append('w')
        engine.append('j')
        engine.append('w')
        val state = engine.append('h')

        assertFalse(state.canCommitOnSpace)
        assertEquals("前缀", state.primaryCandidate?.text)
    }

    @Test
    fun preferConsumedLengthLadderPromotesOneCandidatePerLengthFirst() {
        val ranked = preferConsumedLengthLadder(
            listOf(
                candidate("整句一", "a'b'c'd", 8, 4, 1000, ChineseCandidateKind.SENTENCE),
                candidate("整句二", "a'b'c'd", 8, 4, 990, ChineseCandidateKind.SENTENCE),
                candidate("短句", "a'b'c", 6, 3, 980, ChineseCandidateKind.PHRASE),
                candidate("词组", "a'b", 4, 2, 970, ChineseCandidateKind.PHRASE),
                candidate("单字", "a", 2, 1, 960, ChineseCandidateKind.FALLBACK),
            ),
        ) { it.consumedSyllables }

        assertEquals(
            listOf("整句一", "短句", "词组", "单字", "整句二"),
            ranked.map { it.text },
        )
    }

    @Test
    fun groupedRawCodeUsesShuangpinPairBoundaries() {
        assertEquals("wo'mf'xm'zl'hl", groupShuangpinRawCode("womfxmzlhl"))
        assertEquals("wo'm", groupShuangpinRawCode("wom"))
        assertEquals("", groupShuangpinRawCode(""))
    }

    private fun candidate(
        text: String,
        pinyinKey: String,
        consumedCodeLength: Int,
        consumedSyllables: Int,
        score: Int,
        kind: ChineseCandidateKind = ChineseCandidateKind.FALLBACK,
    ) = ChineseCandidate(
        text = text,
        pinyinKey = pinyinKey,
        consumedCodeLength = consumedCodeLength,
        consumedSyllables = consumedSyllables,
        score = score,
        kind = kind,
    )

    private class FakeLexiconDataSource : ChineseLexiconDataSource {
        val candidateMap = linkedMapOf<String, List<ChineseCandidate>>()
        val contextualOverrides = linkedMapOf<String, List<ChineseCandidate>>()
        var lastLookupContext: String = ""
        var lastRecordedPinyinKey: String = ""
        var lastRecordedText: String = ""
        var lastRecordedContext: String = ""

        override fun lookupCandidates(
            syllables: List<String>,
            contextBefore: String,
            limit: Int,
        ): ChineseCandidateQueryResult {
            lastLookupContext = contextBefore
            val key = syllables.joinToString("'")
            val contextual = contextualOverrides["$contextBefore|$key"]
            val candidates = contextual ?: candidateMap[key].orEmpty()
            return ChineseCandidateQueryResult(candidates.take(limit))
        }

        override fun lookupInitialCandidates(
            initialPrefix: String,
            contextBefore: String,
            limit: Int,
        ): List<ChineseCandidate> = emptyList()

        override fun lookupPrefixedCandidates(
            pinyinPrefix: String,
            consumedCodeLength: Int,
            contextBefore: String,
            limit: Int,
        ): List<ChineseCandidate> = emptyList()

        override fun recordSelection(
            pinyinKey: String,
            text: String,
            contextBefore: String,
        ) {
            lastRecordedPinyinKey = pinyinKey
            lastRecordedText = text
            lastRecordedContext = contextBefore
        }
    }
}
