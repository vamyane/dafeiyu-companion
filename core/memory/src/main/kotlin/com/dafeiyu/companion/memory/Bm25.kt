package com.dafeiyu.companion.memory

import kotlin.math.ln

object Tokenizer {

    private val latin = Regex("[a-z0-9]+")

    fun tokenize(text: String): List<String> {
        val lower = text.lowercase()
        val tokens = mutableListOf<String>()
        latin.findAll(lower).forEach { tokens += it.value }

        val han = lower.filter { it in '\u4e00'..'\u9fff' }
        if (han.length == 1) tokens += han
        for (i in 0 until han.length - 1) {
            tokens += han.substring(i, i + 2)
        }
        return tokens
    }
}

class Bm25(private val docs: List<List<String>>) {

    private val k1 = 1.2
    private val b = 0.75
    private val df = mutableMapOf<String, Int>()
    private val avgdl: Double

    init {
        for (doc in docs) {
            for (term in doc.toSet()) {
                df[term] = (df[term] ?: 0) + 1
            }
        }
        avgdl = if (docs.isEmpty()) 0.0 else docs.sumOf { it.size }.toDouble() / docs.size
    }

    fun score(query: List<String>, index: Int): Double {
        if (docs.isEmpty() || avgdl <= 0.0) return 0.0
        val doc = docs[index]
        if (doc.isEmpty()) return 0.0
        val tf = doc.groupingBy { it }.eachCount()
        var score = 0.0
        for (term in query.distinct()) {
            val frequency = tf[term] ?: continue
            val documentFrequency = df[term] ?: 0
            val idf = ln(1.0 + (docs.size - documentFrequency + 0.5) / (documentFrequency + 0.5))
            score += idf * (frequency * (k1 + 1.0)) /
                (frequency + k1 * (1.0 - b + b * doc.size / avgdl))
        }
        return score
    }
}
