package com.samtex.eidreader.core.util

/** Abstraction over wall-clock time so that timing-dependent logic is testable. */
fun interface Clock {
    fun nowMs(): Long

    companion object {
        val SYSTEM: Clock = Clock { System.currentTimeMillis() }
    }
}
