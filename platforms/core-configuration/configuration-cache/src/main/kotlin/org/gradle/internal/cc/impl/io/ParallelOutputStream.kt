/*
 * Copyright 2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.cc.impl.io

import org.gradle.internal.cc.base.debug
import org.gradle.internal.cc.base.logger
import java.io.OutputStream
import java.nio.Buffer
import java.util.Queue
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread
import kotlin.math.roundToInt


private
typealias Packet = java.nio.ByteBuffer


/**
 * Factory for an [OutputStream] decorator that offloads the writing to a separate thread.
 */
internal
object ParallelOutputStream {

    /**
     * See [PacketPool.packetSize].
     */
    val recommendedBufferSize: Int
        get() = PacketPool.packetSize

    /**
     * Returns an [OutputStream] that offloads writing to the stream returned by [createOutputStream]
     * to a separate thread. The returned stream can only be written to from a single thread at a time.
     *
     * Note that [createOutputStream] will be called in the writing thread.
     *
     * @see QueuedOutputStream
     * @see PacketPool
     */
    fun of(createOutputStream: () -> OutputStream): OutputStream {
        val packets = PacketPool()
        val readyQ = ConcurrentLinkedQueue<Packet>()
        val writer = thread(name = "CC writer", isDaemon = true, priority = Thread.NORM_PRIORITY) {
            try {
                createOutputStream().use { outputStream ->
                    while (true) {
                        val packet = readyQ.poll()
                        if (packet == null) {
                            // give the producer another chance
                            Thread.yield()
                            continue
                        }
                        flip(packet)
                        if (!packet.hasRemaining()) {
                            /** producer is signaling end of stream
                             * see [QueuedOutputStream.close]
                             **/
                            break
                        }
                        try {
                            outputStream.write(packet.array(), 0, packet.remaining())
                        } finally {
                            // always return the packet to the pool
                            makeEmpty(packet)
                            packets.put(packet)
                        }
                    }
                }
            } catch (e: Exception) {
                packets.fail(e)
            } finally {
                // in case of failure, this releases some memory until the
                // producer realizes there was a failure
                readyQ.clear()
            }
            logger.debug {
                "${javaClass.name} writer ${Thread.currentThread()} finished."
            }
        }
        return QueuedOutputStream(packets, readyQ) {
            writer.join()
        }
    }

    private
    fun flip(packet: Buffer) {
        // packet.flip() is not available in Java 8
        packet
            .limit(packet.position())
            .position(0)
    }

    private
    fun makeEmpty(packet: Buffer) {
        packet.limit(packet.capacity())
    }
}


/**
 * An [OutputStream] implementation that writes to buffers taken from a [PacketPool]
 * and posts them to the given [ready queue][readyQ] when they are full.
 */
private
class QueuedOutputStream(
    private val packets: PacketPool,
    private val readyQ: Queue<Packet>,
    private val onClose: () -> Unit,
) : OutputStream() {

    private
    var packet = packets.take()

    override fun write(b: ByteArray, off: Int, len: Int) {
        writeByteArray(b, off, len)
    }

    override fun write(b: Int) {
        packet.put(b.toByte())
        maybeFlush()
    }

    override fun close() {
        // send remaining data
        if (packet.position() > 0) {
            sendPacket()
            takeNextPacket()
        }
        // send a last empty packet to signal the end
        sendPacket()
        onClose()
        packets.rethrowFailureIfAny()
        super.close()
    }

    private
    tailrec fun writeByteArray(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) {
            return
        }
        val remaining = packet.remaining()
        if (remaining >= len) {
            putByteArrayAndFlush(b, off, len)
        } else {
            putByteArrayAndFlush(b, off, remaining)
            writeByteArray(b, off + remaining, len - remaining)
        }
    }

    private
    fun putByteArrayAndFlush(b: ByteArray, off: Int, len: Int) {
        packet.put(b, off, len)
        maybeFlush()
    }

    private
    fun maybeFlush() {
        if (!packet.hasRemaining()) {
            sendPacket()
            takeNextPacket()
        }
    }

    private
    fun sendPacket() {
        readyQ.offer(packet)
    }

    private
    fun takeNextPacket() {
        packet = packets.take()
    }
}


/**
 * Manages a pool of packets of fixed [size][PacketPool.packetSize] allocated on-demand
 * upto a [fixed maximum][PacketPool.maxPackets].
 */
private
class PacketPool {

    companion object {

        /**
         * How many bytes are transferred, at a time, from the producer to the writer thread.
         *
         * The smaller the number the more parallelism between producer and writer (but also greater the synchronization overhead).
         */
        val packetSize = System.getProperty("org.gradle.configuration-cache.internal.packet-size", null)?.toInt()
            ?: 4096

        /**
         * Maximum number of packets to be allocated.
         *
         * Determines the maximum memory working set: [maxPackets] * [packetSize].
         * The default maximum working set is `16MB`.
         */
        val maxPackets = System.getProperty("org.gradle.configuration-cache.internal.max-packets", null)?.toInt()
            ?: 4096

        val packetTimeoutMinutes: Long = System.getProperty("org.gradle.configuration-cache.internal.packet-timeout-minutes", null)?.toLong()
            ?: 30L /* stream can be kept open during the whole configuration phase */
    }

    private
    val packets = ArrayBlockingQueue<Packet>(maxPackets)

    private
    var packetsToAllocate = maxPackets

    @Volatile
    private
    var failure: Exception? = null

    fun put(packet: Packet) {
        require(packet.position() == 0 && packet.remaining() == packet.limit())
        require(packets.offer(packet))
    }

    private
    val packetReuseThreshold = (maxPackets * 0.9).roundToInt()

    fun take(): Packet {
        rethrowFailureIfAny()
        val remainingAllocations = packetsToAllocate
        if (remainingAllocations > 0) {
            if (remainingAllocations < packetReuseThreshold) {
                // try to reuse packets past some threshold
                // to amortize the cost of locking the packets queue
                val reused = packets.poll()
                if (reused != null) {
                    return reused
                }
            }
            --packetsToAllocate
            return Packet.allocate(packetSize)
        }
        return packets.poll(packetTimeoutMinutes, TimeUnit.MINUTES)
            ?: throw TimeoutException("Timed out while waiting for a packet.")
    }

    fun fail(e: Exception) {
        failure = e
    }

    fun rethrowFailureIfAny() {
        failure?.let {
            throw it
        }
    }
}
