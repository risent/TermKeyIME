package com.termkey.ime

enum class ChineseCandidateKind {
    NONE,
    SENTENCE,
    PHRASE,
    FALLBACK,
}

data class ChineseCandidate(
    val text: String,
    val pinyinKey: String,
    val consumedCodeLength: Int,
    val consumedSyllables: Int,
    val score: Int,
    val kind: ChineseCandidateKind,
)

data class ChineseCandidateQueryResult(
    val candidates: List<ChineseCandidate>,
) {
    val primaryCandidateKind: ChineseCandidateKind
        get() = candidates.firstOrNull()?.kind ?: ChineseCandidateKind.NONE
}

data class ChineseInputState(
    val rawCode: String,
    val groupedRawCode: String,
    val decodedPinyin: String,
    val previewText: String,
    val candidates: List<ChineseCandidate>,
    val hasPendingTail: Boolean,
    val primaryCandidate: ChineseCandidate?,
    val primaryCandidateKind: ChineseCandidateKind,
    val canCommitOnSpace: Boolean,
)

interface ChineseLexiconDataSource {
    fun lookupCandidates(
        syllables: List<String>,
        contextBefore: String = "",
        limit: Int = 9,
    ): ChineseCandidateQueryResult

    fun lookupInitialCandidates(
        initialPrefix: String,
        contextBefore: String = "",
        limit: Int = 18,
    ): List<ChineseCandidate>

    fun lookupPrefixedCandidates(
        pinyinPrefix: String,
        consumedCodeLength: Int,
        contextBefore: String = "",
        limit: Int = 18,
    ): List<ChineseCandidate>

    fun recordSelection(
        pinyinKey: String,
        text: String,
        contextBefore: String = "",
    )
}

class NaturalShuangpinEngine(
    private val lexiconStore: ChineseLexiconDataSource? = null,
) {

    private val rawCode = StringBuilder()

    fun append(letter: Char, contextBefore: String = ""): ChineseInputState {
        rawCode.append(letter.lowercaseChar())
        return currentState(contextBefore)
    }

    fun backspace(contextBefore: String = ""): ChineseInputState {
        if (rawCode.isNotEmpty()) {
            rawCode.deleteCharAt(rawCode.lastIndex)
        }
        return currentState(contextBefore)
    }

    fun clear(contextBefore: String = ""): ChineseInputState {
        rawCode.clear()
        return currentState(contextBefore)
    }

    fun hasPending(): Boolean = rawCode.isNotEmpty()

    fun recordSelection(
        state: ChineseInputState,
        candidate: ChineseCandidate,
        contextBefore: String = "",
    ) {
        if (candidate.text.isBlank() || candidate.pinyinKey.isBlank()) return
        lexiconStore?.recordSelection(candidate.pinyinKey, candidate.text, contextBefore)
    }

    fun consumeCandidate(candidate: ChineseCandidate) {
        if (candidate.consumedCodeLength <= 0 || rawCode.isEmpty()) return
        rawCode.delete(0, candidate.consumedCodeLength.coerceAtMost(rawCode.length))
    }

    fun currentState(contextBefore: String = ""): ChineseInputState {
        val code = rawCode.toString()
        if (code.isEmpty()) {
            return ChineseInputState("", "", "", "", emptyList(), false, null, ChineseCandidateKind.NONE, false)
        }
        val groupedRawCode = groupShuangpinRawCode(code)

        val fullPairCount = code.length / 2
        val hasPendingTail = code.length % 2 == 1
        val completePart = code.substring(0, fullPairCount * 2)
        val pendingTail = if (hasPendingTail) code.takeLast(1) else ""

        val syllables = mutableListOf<String>()
        var decodeFailed = false
        completePart.chunked(2).forEach { pair ->
            val syllable = decodePair(pair)
            if (syllable == null) {
                decodeFailed = true
                return@forEach
            }
            syllables += syllable
        }

        if (decodeFailed) {
            return ChineseInputState(
                rawCode = code,
                groupedRawCode = groupedRawCode,
                decodedPinyin = code,
                previewText = code,
                candidates = emptyList(),
                hasPendingTail = hasPendingTail,
                primaryCandidate = null,
                primaryCandidateKind = ChineseCandidateKind.FALLBACK,
                canCommitOnSpace = false,
            )
        }

        val decodedPinyin = buildString {
            append(syllables.joinToString("'"))
            if (pendingTail.isNotEmpty()) {
                if (isNotEmpty()) append(" ")
                append(pendingTail)
            }
        }

        val candidateResult = when {
            !hasPendingTail && syllables.isNotEmpty() -> lookupCandidates(syllables, contextBefore)
            hasPendingTail && syllables.isEmpty() -> ChineseCandidateQueryResult(
                lookupInitialCandidates(pendingTail.first(), contextBefore),
            )
            hasPendingTail && syllables.isNotEmpty() -> lookupPartialCandidates(
                syllables,
                pendingTail.first(),
                code.length,
                contextBefore,
            )
            else -> ChineseCandidateQueryResult(emptyList())
        }
        val primaryCandidate = candidateResult.candidates.firstOrNull()

        val preview = when {
            primaryCandidate != null -> primaryCandidate.text
            decodedPinyin.isNotEmpty() -> decodedPinyin
            else -> code
        }

        return ChineseInputState(
            rawCode = code,
            groupedRawCode = groupedRawCode,
            decodedPinyin = decodedPinyin,
            previewText = preview,
            candidates = candidateResult.candidates,
            hasPendingTail = hasPendingTail,
            primaryCandidate = primaryCandidate,
            primaryCandidateKind = candidateResult.primaryCandidateKind,
            canCommitOnSpace = primaryCandidate != null &&
                !hasPendingTail &&
                primaryCandidate.consumedCodeLength == code.length,
        )
    }

    private fun decodePair(pair: String): String? {
        val normalized = pair.lowercase()
        zeroInitialMap[normalized]?.let { return it }
        if (normalized.length != 2) return null

        val initial = decodeInitial(normalized[0]) ?: return null
        val possibleFinals = finalMap[normalized[1]] ?: return null
        return possibleFinals
            .mapNotNull { combine(initial, it) }
            .firstOrNull { it in validSyllables }
    }

    private fun decodeInitial(ch: Char): String? {
        return when (ch) {
            'i' -> "ch"
            'u' -> "sh"
            'v' -> "zh"
            in standardInitials -> ch.toString()
            else -> null
        }
    }

    private fun combine(initial: String, finalToken: String): String? {
        return when (initial) {
            "y" -> when (finalToken) {
                "i" -> "yi"
                "ia" -> "ya"
                "iao" -> "yao"
                "ie" -> "ye"
                "iu" -> "you"
                "ian" -> "yan"
                "iang" -> "yang"
                "in" -> "yin"
                "ing" -> "ying"
                "iong", "ong" -> "yong"
                "u" -> "wu"
                "ua" -> "wa"
                "uai" -> "wai"
                "uan" -> "yuan"
                "uang" -> "wang"
                "ui" -> "wei"
                "un" -> "yun"
                "uo", "o" -> "wo"
                "v" -> "yu"
                "ue", "ve" -> "yue"
                "ao" -> "yao"
                "an" -> "yan"
                "ang" -> "yang"
                "ou" -> "you"
                "eng" -> "weng"
                "a" -> "ya"
                "e" -> "ye"
                else -> null
            }
            "w" -> when (finalToken) {
                "a" -> "wa"
                "ai" -> "wai"
                "u" -> "wu"
                "ua" -> "wa"
                "uai" -> "wai"
                "an" -> "wan"
                "ang" -> "wang"
                "en" -> "wen"
                "uan" -> "wan"
                "uang" -> "wang"
                "ei" -> "wei"
                "ui" -> "wei"
                "un" -> "wen"
                "uo", "o" -> "wo"
                "eng" -> "weng"
                else -> null
            }
            "j", "q", "x" -> when (finalToken) {
                "v" -> initial + "u"
                "ve" -> initial + "ue"
                else -> initial + finalToken
            }
            else -> initial + finalToken
        }
    }

    private fun lookupCandidates(
        syllables: List<String>,
        contextBefore: String,
    ): ChineseCandidateQueryResult {
        if (syllables.isEmpty()) {
            return ChineseCandidateQueryResult(emptyList())
        }

        val lexiconCandidates = lexiconStore?.lookupCandidates(syllables, contextBefore)?.candidates.orEmpty()
        val fallbackCandidates = fallbackCandidatesForSyllables(syllables)
        val preferredTexts = preferredPhraseTextsForSyllables(syllables)
        return ChineseCandidateQueryResult(
            mergeCandidates(
                primary = lexiconCandidates,
                secondary = fallbackCandidates,
                limit = if (syllables.size == 1) 32 else 9,
                preferredTexts = preferredTexts,
            ),
        )
    }

    private fun lookupInitialCandidates(rawInitial: Char, contextBefore: String): List<ChineseCandidate> {
        val prefix = initialCandidatePrefix(rawInitial) ?: return emptyList()
        lexiconStore?.lookupInitialCandidates(prefix, contextBefore)?.takeIf { it.isNotEmpty() }?.let {
            return it
        }
        return rawSingleCharCandidates
            .filterKeys { it.startsWith(prefix) }
            .values
            .flatMap { chars -> chars.map { it.toString() } }
            .distinct()
            .take(18)
            .mapIndexed { index, text ->
                ChineseCandidate(
                    text = text,
                    pinyinKey = prefix,
                    consumedCodeLength = 1,
                    consumedSyllables = 1,
                    score = 180 - index,
                    kind = ChineseCandidateKind.FALLBACK,
                )
            }
    }

    private fun lookupPartialCandidates(
        syllables: List<String>,
        rawInitial: Char,
        codeLength: Int,
        contextBefore: String,
    ): ChineseCandidateQueryResult {
        val initialPrefix = initialCandidatePrefix(rawInitial)
            ?: return ChineseCandidateQueryResult(emptyList())
        val prefix = buildString {
            append(syllables.joinToString("'"))
            append("'")
            append(initialPrefix)
        }
        val prefixed = lexiconStore?.lookupPrefixedCandidates(prefix, codeLength, contextBefore).orEmpty()
        val completed = lookupCandidates(syllables, contextBefore).candidates
        val merged = preferConsumedLengthLadder(
            (prefixed + completed)
            .distinctBy { "${it.text}|${it.consumedCodeLength}|${it.pinyinKey}" }
            .sortedWith(
                compareByDescending<ChineseCandidate> { it.consumedCodeLength == codeLength }
                    .thenByDescending { it.score }
                    .thenByDescending { it.consumedSyllables }
                    .thenByDescending { it.text.length },
            ),
        ) { it.consumedSyllables }
            .take(18)
        return ChineseCandidateQueryResult(merged)
    }

    private fun initialCandidatePrefix(rawInitial: Char): String? {
        return decodeInitial(rawInitial) ?: when (rawInitial.lowercaseChar()) {
            'a', 'e', 'o' -> rawInitial.lowercaseChar().toString()
            else -> null
        }
    }

    private fun fallbackCandidatesForSyllables(syllables: List<String>): List<ChineseCandidate> {
        val explicit = phraseCandidates[syllables.joinToString("'")].orEmpty()
        val generated = when (syllables.size) {
            1 -> singleCharCandidates[syllables.first()].orEmpty()
            else -> generatePhraseCandidates(syllables)
        }
        val kind = when {
            syllables.size >= 3 -> ChineseCandidateKind.SENTENCE
            syllables.size >= 2 -> ChineseCandidateKind.PHRASE
            else -> ChineseCandidateKind.FALLBACK
        }
        val consumedCodeLength = syllables.size * 2
        val pinyinKey = syllables.joinToString("'")
        return (explicit + generated)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(9)
            .mapIndexed { index, text ->
                val explicitCandidate = text in explicit
                val baseScore = when {
                    explicitCandidate && syllables.size >= 3 -> 1180
                    explicitCandidate && syllables.size >= 2 -> 1080
                    explicitCandidate -> 260
                    syllables.size >= 3 -> 240
                    syllables.size >= 2 -> 180
                    else -> 90
                }
                ChineseCandidate(
                    text = text,
                    pinyinKey = pinyinKey,
                    consumedCodeLength = consumedCodeLength,
                    consumedSyllables = syllables.size,
                    score = baseScore + text.length * 12 - index * 10,
                    kind = kind,
                )
            }
    }

    private fun preferredPhraseTextsForSyllables(syllables: List<String>): Set<String> {
        if (syllables.size < 2) return emptySet()
        return phraseCandidates[syllables.joinToString("'")]
            .orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun mergeCandidates(
        primary: List<ChineseCandidate>,
        secondary: List<ChineseCandidate>,
        limit: Int,
        preferredTexts: Set<String> = emptySet(),
    ): List<ChineseCandidate> {
        return preferConsumedLengthLadder(
            (primary + secondary)
            .groupBy { "${it.text}|${it.pinyinKey}|${it.consumedCodeLength}" }
            .values
            .mapNotNull { group -> group.maxByOrNull { it.score } }
            .sortedWith(
                compareByDescending<ChineseCandidate> { it.text in preferredTexts }
                    .thenByDescending { it.score }
                    .thenByDescending { it.consumedCodeLength }
                    .thenByDescending { it.kind == ChineseCandidateKind.SENTENCE }
                    .thenByDescending { it.kind == ChineseCandidateKind.PHRASE }
                    .thenByDescending { it.text.length }
                    .thenBy { it.text },
            ),
        ) { it.consumedSyllables }
            .take(limit)
    }

    private fun generatePhraseCandidates(syllables: List<String>): List<String> {
        val perSyllable = syllables.map { syllable ->
            singleCharCandidates[syllable].orEmpty().take(if (syllables.size > 2) 2 else 3)
        }
        if (perSyllable.any { it.isEmpty() }) {
            return emptyList()
        }

        val results = mutableListOf<String>()
        fun dfs(index: Int, current: StringBuilder) {
            if (results.size >= 9) return
            if (index == perSyllable.size) {
                results += current.toString()
                return
            }
            val beforeLength = current.length
            perSyllable[index].forEach { candidate ->
                current.append(candidate)
                dfs(index + 1, current)
                current.setLength(beforeLength)
                if (results.size >= 9) return
            }
        }

        dfs(0, StringBuilder())
        return results
    }

    companion object {
        private val standardInitials = setOf(
            'b', 'p', 'm', 'f', 'd', 't', 'n', 'l',
            'g', 'k', 'h', 'j', 'q', 'x', 'r',
            'z', 'c', 's', 'y', 'w',
        )

        private val zeroInitialMap = mapOf(
            "aa" to "a",
            "ai" to "ai",
            "an" to "an",
            "ah" to "ang",
            "ao" to "ao",
            "ee" to "e",
            "ei" to "ei",
            "en" to "en",
            "eg" to "eng",
            "er" to "er",
            "oo" to "o",
            "ou" to "ou",
        )

        private val finalMap = mapOf(
            'a' to listOf("a"),
            'e' to listOf("e"),
            'i' to listOf("i"),
            'o' to listOf("o", "uo"),
            'u' to listOf("u"),
            'v' to listOf("v", "ui"),
            'q' to listOf("iu"),
            'w' to listOf("ia", "ua"),
            'r' to listOf("uan"),
            't' to listOf("ue", "ve"),
            'y' to listOf("ing", "uai"),
            'p' to listOf("un"),
            's' to listOf("iong", "ong"),
            'd' to listOf("iang", "uang"),
            'f' to listOf("en"),
            'g' to listOf("eng"),
            'h' to listOf("ang"),
            'j' to listOf("an"),
            'k' to listOf("ao"),
            'l' to listOf("ai"),
            'z' to listOf("ei"),
            'x' to listOf("ie"),
            'c' to listOf("iao"),
            'b' to listOf("ou"),
            'n' to listOf("in"),
            'm' to listOf("ian"),
        )

        private val rawSingleCharCandidates = mapOf(
            "a" to "啊阿呵吖",
            "ai" to "爱矮挨哎艾",
            "an" to "按安案岸暗",
            "ang" to "昂肮盎",
            "ao" to "奥熬凹袄傲",
            "ba" to "把吧八巴爸",
            "bai" to "百白败摆柏",
            "ban" to "办半班版般",
            "bang" to "帮棒邦榜绑",
            "bao" to "报包保宝抱",
            "bei" to "被北倍备背",
            "ben" to "本奔苯笨",
            "beng" to "崩甭泵绷",
            "bi" to "比必笔毕币",
            "bian" to "边变便编遍",
            "biao" to "表标彪婊",
            "bie" to "别憋鳖瘪",
            "bin" to "宾斌滨彬濒",
            "bing" to "并病兵冰饼",
            "bo" to "波博播伯勃",
            "bu" to "不部步布补",
            "ca" to "擦嚓礤",
            "cai" to "才采材财菜",
            "can" to "参残惨餐灿",
            "cang" to "藏仓苍沧",
            "cao" to "草操曹槽糙",
            "ce" to "侧策册测厕",
            "cha" to "查差茶插叉",
            "chai" to "柴拆豺钗",
            "chan" to "产缠阐掺馋",
            "chang" to "长常场唱厂",
            "chao" to "朝超潮炒抄",
            "che" to "车彻撤扯",
            "chen" to "陈称沉晨臣",
            "cheng" to "成城程称承",
            "chi" to "吃持迟池尺",
            "chong" to "冲虫崇充宠",
            "chou" to "抽愁臭仇酬",
            "chu" to "出处初除触",
            "chuan" to "传船穿串川",
            "chuang" to "创窗床闯幢",
            "chui" to "吹垂锤炊捶",
            "chun" to "春纯唇醇椿",
            "chuo" to "戳绰辍龊",
            "ci" to "次此词辞慈",
            "cong" to "从丛聪匆葱",
            "cou" to "凑楱辏腠",
            "cu" to "粗促醋簇卒",
            "cuan" to "窜攒篡蹿撺",
            "cui" to "催脆翠摧崔",
            "cun" to "村存寸蹲",
            "cuo" to "错措挫撮搓",
            "da" to "大达答打搭",
            "dai" to "代带待戴袋",
            "dan" to "但单担淡蛋",
            "dang" to "当党档荡挡",
            "dao" to "到道导倒岛",
            "de" to "的得德锝",
            "deng" to "等灯登邓凳",
            "di" to "地第提低底",
            "dian" to "点电店典垫",
            "diao" to "调掉雕吊钓",
            "die" to "跌叠蝶爹碟",
            "ding" to "定顶丁订钉",
            "diu" to "丢铥",
            "dong" to "东动懂冬洞",
            "dou" to "都斗豆逗抖",
            "du" to "度都读独督",
            "duan" to "端短段断缎",
            "dui" to "对队堆兑怼",
            "dun" to "顿盾吨敦蹲",
            "duo" to "多夺朵躲舵",
            "e" to "饿额俄鹅讹",
            "en" to "恩摁蒽",
            "eng" to "嗯",
            "er" to "二而儿耳尔",
            "fa" to "发法罚伐乏",
            "fan" to "反范饭翻凡",
            "fang" to "方放房防访",
            "fei" to "非费飞肥菲",
            "fen" to "分份粉纷奋",
            "feng" to "风封峰丰疯",
            "fo" to "佛",
            "fou" to "否缶",
            "fu" to "服复府负福",
            "ga" to "嘎噶尬",
            "gai" to "该改概盖钙",
            "gan" to "干感敢赶甘",
            "gang" to "刚港钢纲岗",
            "gao" to "高告稿搞糕",
            "ge" to "个各格歌哥",
            "gei" to "给",
            "gen" to "跟根亘",
            "geng" to "更耕梗庚羹",
            "gong" to "工公共功攻",
            "gou" to "够构购沟狗",
            "gu" to "古故股顾谷",
            "gua" to "挂刮瓜寡卦",
            "guai" to "怪拐乖",
            "guan" to "关管官观馆",
            "guang" to "光广逛咣",
            "gui" to "规贵归鬼桂",
            "gun" to "滚棍辊衮",
            "guo" to "国过果锅郭",
            "ha" to "哈蛤",
            "hai" to "还海害孩骸",
            "han" to "汉含汗喊寒",
            "hang" to "行航巷杭夯",
            "hao" to "好号浩豪毫",
            "he" to "和合河喝何",
            "hei" to "黑嘿",
            "hen" to "很狠恨痕",
            "heng" to "横衡恒亨哼",
            "hong" to "红宏洪轰虹",
            "hou" to "后候厚猴喉",
            "hu" to "和胡护户湖",
            "hua" to "话化花画华",
            "huai" to "坏怀淮槐",
            "huan" to "还换欢环患",
            "huang" to "黄皇慌晃荒",
            "hui" to "会回灰汇惠",
            "hun" to "婚混昏魂浑",
            "huo" to "或活火获货",
            "ji" to "机几及记级",
            "jia" to "家加假价架",
            "jian" to "见件间建简",
            "jiang" to "将讲江降奖",
            "jiao" to "叫交角教较",
            "jie" to "接节界解结",
            "jin" to "进今金近尽",
            "jing" to "经京精境景",
            "jiong" to "炯窘迥",
            "jiu" to "就九旧久救",
            "ju" to "据局举句剧",
            "juan" to "卷捐倦圈鹃",
            "jue" to "决绝觉角爵",
            "jun" to "军均君郡俊",
            "ka" to "卡咖喀",
            "kai" to "开凯慨楷",
            "kan" to "看刊砍堪勘",
            "kang" to "康抗扛慷糠",
            "kao" to "考靠烤拷",
            "ke" to "可科克客课",
            "ken" to "肯啃恳垦",
            "keng" to "坑吭铿",
            "kong" to "空控孔恐",
            "kou" to "口扣寇叩抠",
            "ku" to "库苦酷裤枯",
            "kua" to "跨夸垮挎",
            "kuai" to "快块筷会",
            "kuan" to "宽款髋",
            "kuang" to "况框狂矿旷",
            "kui" to "亏愧馈奎葵",
            "kun" to "困昆坤捆",
            "kuo" to "扩括阔廓",
            "la" to "拉啦辣腊蜡",
            "lai" to "来赖莱癞",
            "lan" to "蓝兰烂栏览",
            "lang" to "浪郎朗廊狼",
            "lao" to "老劳牢捞烙",
            "le" to "了乐勒肋",
            "lei" to "类累雷泪勒",
            "leng" to "冷愣棱",
            "li" to "里理力利立",
            "lia" to "俩",
            "lian" to "连脸练联恋",
            "liang" to "两量亮良辆",
            "liao" to "了料聊疗辽",
            "lie" to "列烈裂劣猎",
            "lin" to "林临邻琳淋",
            "ling" to "领令零灵铃",
            "liu" to "流六留刘柳",
            "long" to "龙隆笼拢聋",
            "lou" to "楼漏露搂陋",
            "lu" to "路录陆露鲁",
            "luan" to "乱卵栾鸾",
            "lun" to "论轮伦仑",
            "luo" to "罗落络洛裸",
            "ma" to "吗妈马嘛麻",
            "mai" to "买卖麦脉埋",
            "man" to "满慢曼漫蛮",
            "mang" to "忙芒盲茫莽",
            "mao" to "毛猫冒帽茂",
            "me" to "么麽",
            "mei" to "没每美妹眉",
            "men" to "们门闷扪焖",
            "meng" to "梦蒙猛盟孟",
            "mi" to "密米迷秘蜜",
            "mian" to "面免棉眠绵",
            "miao" to "秒苗庙妙描",
            "mie" to "灭蔑篾",
            "min" to "民敏闽皿",
            "ming" to "名明命鸣铭",
            "miu" to "谬",
            "mo" to "么模末莫摸",
            "mou" to "某谋牟眸",
            "mu" to "目木母幕墓",
            "na" to "那拿哪纳娜",
            "nai" to "乃奶耐奈鼐",
            "nan" to "南难男楠喃",
            "nang" to "囊攮馕",
            "nao" to "脑闹恼挠孬",
            "ne" to "呢哪讷",
            "nei" to "内那馁",
            "nen" to "嫩",
            "neng" to "能",
            "ni" to "你尼呢泥拟",
            "nian" to "年念粘捻碾",
            "niang" to "娘酿",
            "niao" to "鸟尿袅",
            "nie" to "捏聂孽镍涅",
            "nin" to "您",
            "ning" to "宁凝拧泞柠",
            "niu" to "牛纽扭钮拗",
            "nong" to "农浓弄脓",
            "nou" to "耨",
            "nu" to "怒努奴弩",
            "nuan" to "暖",
            "nuo" to "诺挪懦糯",
            "nve" to "虐疟",
            "o" to "哦噢喔",
            "ou" to "偶欧殴藕呕",
            "pa" to "怕爬帕趴琶",
            "pai" to "派排拍牌徘",
            "pan" to "盘判攀盼潘",
            "pang" to "旁胖庞乓",
            "pao" to "跑炮泡抛袍",
            "pei" to "配陪培佩胚",
            "pen" to "喷盆湓",
            "peng" to "朋碰彭棚鹏",
            "pi" to "批皮披疲匹",
            "pian" to "片篇偏骗便",
            "piao" to "票漂飘瓢嫖",
            "pie" to "撇瞥丿",
            "pin" to "品贫拼频聘",
            "ping" to "平评瓶凭萍",
            "po" to "破迫坡颇婆",
            "pou" to "剖裒",
            "pu" to "普铺谱扑葡",
            "qi" to "起其期器气",
            "qia" to "恰卡掐洽",
            "qian" to "前千钱签潜",
            "qiang" to "强枪墙抢腔",
            "qiao" to "桥敲悄巧乔",
            "qie" to "且切窃茄怯",
            "qin" to "亲勤侵琴禽",
            "qing" to "请情清轻青",
            "qiong" to "穷琼穹",
            "qiu" to "求球秋丘囚",
            "qu" to "去区取曲趣",
            "quan" to "全权圈劝泉",
            "que" to "却确缺雀瘸",
            "qun" to "群裙逡",
            "ran" to "然燃染冉",
            "rang" to "让嚷壤攘",
            "rao" to "绕扰饶",
            "re" to "热惹",
            "ren" to "人认任仁忍",
            "reng" to "仍扔",
            "ri" to "日",
            "rong" to "容荣融绒蓉",
            "rou" to "肉柔揉蹂",
            "ru" to "如入乳儒蠕",
            "ruan" to "软阮",
            "rui" to "瑞锐蕊芮",
            "run" to "润闰",
            "ruo" to "若弱偌",
            "sa" to "撒洒萨",
            "sai" to "赛塞腮",
            "san" to "三散伞叁",
            "sang" to "丧桑嗓",
            "sao" to "扫骚嫂臊",
            "se" to "色涩瑟",
            "sen" to "森",
            "seng" to "僧",
            "sha" to "啥杀沙纱傻",
            "shai" to "晒筛色",
            "shan" to "山闪善衫陕",
            "shang" to "上商伤尚赏",
            "shao" to "少烧绍稍哨",
            "she" to "社设射舍蛇",
            "shen" to "什么身深神申",
            "sheng" to "生声省胜升",
            "shi" to "是时事十使",
            "shou" to "手受收首守",
            "shu" to "数书树输属",
            "shua" to "刷耍",
            "shuai" to "帅甩衰摔",
            "shuan" to "栓拴涮",
            "shuang" to "双霜爽",
            "shui" to "水谁睡税",
            "shun" to "顺瞬舜吮",
            "shuo" to "说数硕朔烁",
            "si" to "四思死司丝",
            "song" to "送松宋诵颂",
            "sou" to "搜艘嗽叟",
            "su" to "苏速素诉俗",
            "suan" to "算酸蒜",
            "sui" to "岁随碎虽遂",
            "sun" to "孙损笋荪",
            "suo" to "所缩锁索梭",
            "ta" to "他她它塔踏",
            "tai" to "太台态抬胎",
            "tan" to "谈探坦摊叹",
            "tang" to "堂唐糖躺汤",
            "tao" to "讨套桃逃涛",
            "te" to "特忑铽",
            "teng" to "疼腾藤滕",
            "ti" to "提题体替梯",
            "tian" to "天田添填甜",
            "tiao" to "条跳挑调迢",
            "tie" to "铁贴帖",
            "ting" to "听停庭挺亭",
            "tong" to "同通统痛铜",
            "tou" to "头投偷透",
            "tu" to "图土突徒途",
            "tuan" to "团湍疃",
            "tui" to "推退腿褪",
            "tun" to "吞屯臀豚",
            "tuo" to "托脱拖妥拓",
            "wa" to "瓦挖哇蛙",
            "wai" to "外歪崴",
            "wan" to "完万晚玩湾",
            "wang" to "网王望往忘",
            "wei" to "为位委未围",
            "wen" to "文问闻稳温",
            "weng" to "翁瓮嗡",
            "wo" to "我握窝卧沃",
            "wu" to "无五物务误",
            "xi" to "系西希细喜",
            "xia" to "下夏吓峡瞎",
            "xian" to "现先线县限",
            "xiang" to "想向象相像",
            "xiao" to "小笑校消效",
            "xie" to "些写谢鞋协",
            "xin" to "新心信辛欣",
            "xing" to "行性形星兴",
            "xiong" to "雄兄熊胸",
            "xiu" to "修秀袖绣休",
            "xu" to "需许续序须",
            "xuan" to "选宣旋悬玄",
            "xue" to "学雪血穴薛",
            "xun" to "寻讯训迅巡",
            "ya" to "亚压呀牙雅",
            "yan" to "眼言研严演",
            "yang" to "样养央杨阳",
            "yao" to "要药摇遥咬",
            "ye" to "也业夜叶爷",
            "yi" to "一以已意议",
            "yin" to "因音引印银",
            "ying" to "应影英营硬",
            "yong" to "用永拥勇咏",
            "you" to "有又由友右",
            "yu" to "于与语育鱼",
            "yuan" to "元原员院远",
            "yue" to "月越约乐悦",
            "yun" to "云运允晕韵",
            "za" to "杂砸咋",
            "zai" to "在再载灾仔",
            "zan" to "赞暂攒咱",
            "zang" to "藏脏葬赃",
            "zao" to "早造遭噪糟",
            "ze" to "则责择泽仄",
            "zei" to "贼",
            "zen" to "怎谮",
            "zeng" to "增赠曾憎",
            "zha" to "这查炸扎闸",
            "zhai" to "宅摘债窄斋",
            "zhan" to "站战展占粘",
            "zhang" to "张长章掌涨",
            "zhao" to "找照着招赵",
            "zhe" to "这着者折浙",
            "zhen" to "真阵镇振针",
            "zheng" to "正整政证争",
            "zhi" to "之只知直制",
            "zhong" to "中种重众终",
            "zhou" to "周州洲轴宙",
            "zhu" to "主住注诸猪",
            "zhua" to "抓爪",
            "zhuai" to "拽转",
            "zhuan" to "转专传赚砖",
            "zhuang" to "装状庄撞壮",
            "zhui" to "追坠缀椎锥",
            "zhun" to "准谆",
            "zhuo" to "着桌捉琢浊",
            "zi" to "子自字资紫",
            "zong" to "总宗纵踪棕",
            "zou" to "走奏邹揍",
            "zu" to "组族足祖租",
            "zuan" to "钻纂攥",
            "zui" to "最罪嘴醉",
            "zun" to "尊遵樽",
            "zuo" to "做作坐左昨",
        )

        private val phraseCandidates = mapOf(
            "ni'hao" to listOf("你好"),
            "xie'xie" to listOf("谢谢"),
            "zhong'guo" to listOf("中国"),
            "zhong'wen" to listOf("中文"),
            "shu'ru" to listOf("输入"),
            "shu'ru'fa" to listOf("输入法"),
            "pin'yin" to listOf("拼音"),
            "shuang'pin" to listOf("双拼"),
            "zi'ran'ma" to listOf("自然码"),
            "han'zi" to listOf("汉字"),
            "wo'men" to listOf("我们"),
            "ni'men" to listOf("你们"),
            "ta'men" to listOf("他们", "她们"),
            "jin'tian" to listOf("今天"),
            "ming'tian" to listOf("明天"),
            "xian'zai" to listOf("现在"),
            "ke'yi" to listOf("可以"),
            "zhe'ge" to listOf("这个"),
            "na'ge" to listOf("那个"),
            "shen'me" to listOf("什么"),
            "zhong'duan" to listOf("终端"),
            "jian'pan" to listOf("键盘"),
            "gong'zuo" to listOf("工作"),
            "dian'nao" to listOf("电脑"),
            "shou'ji" to listOf("手机"),
            "ru'guo" to listOf("如果"),
            "dan'shi" to listOf("但是"),
            "yin'wei" to listOf("因为"),
            "suo'yi" to listOf("所以"),
            "ce'shi" to listOf("测试"),
            "wen'ti" to listOf("问题"),
            "mei'you" to listOf("没有"),
            "ke'hu'duan" to listOf("客户端"),
        )

        private val singleCharCandidates = rawSingleCharCandidates.mapValues { (_, chars) ->
            chars.map { it.toString() }
        }

        private val validSyllables: Set<String> = buildSet {
            addAll(singleCharCandidates.keys)
            phraseCandidates.keys.forEach { key ->
                addAll(key.split("'"))
            }
            addAll(zeroInitialMap.values)
        }
    }
}
