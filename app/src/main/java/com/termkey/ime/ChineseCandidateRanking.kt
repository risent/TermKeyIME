package com.termkey.ime

internal fun <T> preferConsumedLengthLadder(
    items: List<T>,
    consumedLengthSelector: (T) -> Int,
): List<T> {
    if (items.size <= 1) return items

    val firstConsumedLength = consumedLengthSelector(items.first())
    val firstIndexByConsumedLength = linkedMapOf(firstConsumedLength to 0)
    items.forEachIndexed { index, item ->
        if (index == 0) return@forEachIndexed
        val consumedLength = consumedLengthSelector(item)
        if (consumedLength in 1 until firstConsumedLength) {
            firstIndexByConsumedLength.putIfAbsent(consumedLength, index)
        }
    }

    val promotedIndexes = buildList {
        add(0)
        for (consumedLength in (firstConsumedLength - 1) downTo 1) {
            firstIndexByConsumedLength[consumedLength]?.let { add(it) }
        }
    }
    val promotedIndexSet = promotedIndexes.toHashSet()

    return buildList(items.size) {
        promotedIndexes.forEach { add(items[it]) }
        items.forEachIndexed { index, item ->
            if (index !in promotedIndexSet) {
                add(item)
            }
        }
    }
}

internal fun groupShuangpinRawCode(rawCode: String): String {
    if (rawCode.isBlank()) return ""
    return rawCode.chunked(2).joinToString("'")
}
