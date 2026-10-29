package cn.coldlake.stellar.opensource.playback.reporting

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

internal data class ReportResult(val state: DeliveryState = DeliveryState.Acknowledged,
    val error: String? = null, val retryAfterMs: Long = 0, val baseline: ServerBaseline? = null)
internal interface MediaServerReportAdapter : AutoCloseable {
    val target: String
    val baseline: ServerBaseline
    suspend fun send(fact: PlaybackFact): ReportResult
}
internal data class ReportEnvelope(val key: PlaybackRecordKey?, val revision: Long, val fact: PlaybackFact,
    val adapter: MediaServerReportAdapter)

/** Shared by the application, including overlapping player hosts. One in-flight request per device/account. */
internal class PlaybackReportDispatcher(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val delivered: suspend (ReportEnvelope, ReportResult) -> Unit,
    private val jitter: (Long) -> Long = { it + kotlin.random.Random.nextLong(0, (it / 5).coerceAtLeast(1)) },
    private val requestTimeoutMs: Long = 5_000,
    private val closeTimeoutMs: Long = 15_000,
) {
    private class Session(val start: ReportEnvelope) {
        var latest: ReportEnvelope? = start
        var finish: ReportEnvelope? = null
        var attempted = false
        var started = false
        var blocked = false
        var blockedResult: ReportResult? = null
        var attempts = 0
        var retryAt = 0L
        var deadline = Long.MAX_VALUE
    }
    private class Mutation(val action: suspend () -> Unit, val result: CompletableDeferred<Unit>)
    private class Lane {
        val commands = ArrayDeque<Mutation>()
        val wake = Channel<Unit>(Channel.CONFLATED)
        var current: Session? = null
        var next: Session? = null
    }
    private val lanes = mutableMapOf<String, Lane>()

    fun submit(event: ReportEnvelope) = synchronized(lanes) {
        val created = event.adapter.target !in lanes
        val lane = lanes.getOrPut(event.adapter.target) { Lane() }
        if (event.fact.kind == ReportKind.START) {
            val new = Session(event)
            if (lane.current == null) lane.current = new
            else if (lane.current!!.start.fact.session != event.fact.session) {
                lane.next?.let { abandoned ->
                    scope.launch { delivered(abandoned.finish ?: abandoned.latest ?: abandoned.start, ReportResult(DeliveryState.Unconfirmed, "Superseded")); abandoned.start.adapter.close() }
                }
                lane.next = new
            }
        } else {
            val session = listOfNotNull(lane.current, lane.next).firstOrNull { it.start.fact.session == event.fact.session }
                ?: run { if (created) lanes.remove(event.adapter.target); return@synchronized }
            if (session.finish != null) return@synchronized
            if (event.fact.kind == ReportKind.FINISH) {
                session.finish = event; session.deadline = now() + closeTimeoutMs
            } else {
                session.latest = event.copy(fact = event.fact.copy(reasons = event.fact.reasons + session.latest?.fact?.reasons.orEmpty()))
            }
        }
        lane.wake.trySend(Unit)
        if (created) scope.launch { run(event.adapter.target, lane) }
    }

    fun revoke(target: String, session: String) = synchronized(lanes) {
        val lane = lanes[target] ?: return@synchronized
        listOfNotNull(lane.current, lane.next).filter { it.start.fact.session == session }.forEach {
            it.blocked = true
            it.finish = (it.latest ?: it.start).let { event -> event.copy(fact = event.fact.copy(kind = ReportKind.FINISH)) }
            it.deadline = now()
        }
        lane.wake.trySend(Unit)
    }

    suspend fun command(target: String, action: suspend () -> Unit) {
        val completion = CompletableDeferred<Unit>()
        synchronized(lanes) {
            val created = target !in lanes
            val lane = lanes.getOrPut(target) { Lane() }
            lane.commands.addLast(Mutation(action, completion))
            lane.wake.trySend(Unit)
            if (created) scope.launch { run(target, lane) }
        }
        try { completion.await() } finally { if (!completion.isCompleted) completion.cancel() }
    }

    private suspend fun run(target: String, lane: Lane) {
        try {
            while (currentCoroutineContext().isActive) {
                // This worker awaits every request. Between requests, commands may run even
                // while a playback session remains open; attempted records session history.
                val command = synchronized(lanes) { lane.commands.removeFirstOrNull() }
                if (command != null) {
                    if (command.result.isCancelled) continue
                    try { withTimeout(requestTimeoutMs) { command.action() }; command.result.complete(Unit) }
                    catch (e: Exception) { command.result.completeExceptionally(e) }
                    continue
                }
                var discarded: Pair<Session, String>? = null
                var commandArrived = false
                var waitMs = Long.MAX_VALUE
                val event = synchronized(lanes) {
                    val s = lane.current ?: lane.next?.also { lane.current = it; lane.next = null }
                    if (s == null) {
                        if (lane.commands.isNotEmpty()) { commandArrived = true; return@synchronized null }
                        lanes.remove(target); return
                    }
                    val final = s.finish
                    when {
                        final != null && (!s.attempted || s.blocked || now() >= s.deadline) -> {
                            discarded = s to if (!s.attempted) "NotStarted" else "CloseExpired"
                            lane.current = lane.next; lane.next = null; null
                        }
                        s.blocked -> null
                        now() < s.retryAt -> { waitMs = minOf(s.retryAt, s.deadline) - now(); null }
                        final != null -> final
                        !s.started -> (s.latest ?: s.start).let { it.copy(fact = it.fact.copy(kind = ReportKind.START)) }
                        else -> s.latest?.takeIf { it.fact.kind != ReportKind.START }
                    }?.also { s.attempted = true }
                }
                if (commandArrived) continue
                discarded?.let { (s, why) ->
                    delivered(s.finish ?: s.latest ?: s.start, s.blockedResult ?: ReportResult(DeliveryState.Unconfirmed, why))
                    s.start.adapter.close()
                }
                if (discarded != null) continue
                if (event == null) {
                    if (waitMs == Long.MAX_VALUE) lane.wake.receive() else withTimeoutOrNull(waitMs.coerceAtLeast(1)) { lane.wake.receive() }
                    continue
                }
                val deadline = synchronized(lanes) { lane.current?.deadline ?: Long.MAX_VALUE }
                val result = try {
                    withTimeout(minOf(requestTimeoutMs, (deadline - now()).coerceAtLeast(1))) { event.adapter.send(event.fact) }
                } catch (_: TimeoutCancellationException) { ReportResult(DeliveryState.Retryable, "Timeout") }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { ReportResult(DeliveryState.Retryable, "Network") }
                delivered(event, result)
                var release: MediaServerReportAdapter? = null
                synchronized(lanes) {
                    val s = lane.current ?: return@synchronized
                    if (s.start.fact.session != event.fact.session) return@synchronized
                    when (result.state) {
                        DeliveryState.Acknowledged -> {
                            s.attempts = 0; s.retryAt = 0
                            if (event.fact.kind == ReportKind.FINISH) {
                                release = s.start.adapter; lane.current = lane.next; lane.next = null
                            } else {
                                s.started = true
                                if ((s.latest?.fact?.sequence ?: 0) <= event.fact.sequence) s.latest = null
                            }
                        }
                        DeliveryState.Retryable -> {
                            val backoff = longArrayOf(2_000, 5_000, 10_000, 30_000)[s.attempts.coerceAtMost(3)]
                            s.attempts = (s.attempts + 1).coerceAtMost(3)
                            s.retryAt = now() + maxOf(jitter(backoff), result.retryAfterMs)
                        }
                        else -> { s.blocked = true; s.blockedResult = result }
                    }
                }
                release?.close()
            }
        } finally {
            synchronized(lanes) { if (lanes[target] === lane) lanes.remove(target) }
            lane.current?.start?.adapter?.close(); lane.next?.start?.adapter?.close()
            lane.commands.forEach { it.result.cancel() }; lane.wake.close()
        }
    }
}
