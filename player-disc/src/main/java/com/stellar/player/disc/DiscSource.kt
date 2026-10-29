package com.stellar.player.disc

import java.io.Closeable

/** Random access source shared by DVD and Blu-ray. Never includes credentials in native URLs. */
abstract class DiscReader : Closeable {
    abstract fun readAt(name: String, offset: Long, length: Int): ByteArray?
    open fun cancelRead() {}
}

data class DiscTitle(val number: Int, val durationMs: Long, val chapters: List<Long>)
interface DiscSource : Closeable {
    val titles: List<DiscTitle>
    val bluray: Boolean get() = false
    fun address(title: Int): String
    fun openMenu(): DiscMenu?
}
