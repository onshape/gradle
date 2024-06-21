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
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.util.Queue
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread


/**
 * Factory for an [OutputStream] decorator that offloads the writing to a separate thread.
 */
internal
object ParallelOutputStream {

    /**
     * See [ByteBufferPool.bufferCapacity].
     */
    val bufferCapacity: Int
        get() = ByteBufferPool.bufferCapacity

    /**
     * Returns an [OutputStream] that offloads writing to the stream returned by [createOutputStream]
     * to a separate thread.
     *
     * Note that [createOutputStream] will be called in the writing thread.
     *
     * @see QueuedOutputStream
     * @see ByteBufferPool
     */
    fun of(createOutputStream: () -> OutputStream): OutputStream {
        val buffers = ByteBufferPool()
        val ready = ConcurrentLinkedQueue<ByteBuffer>()
        val writer = thread(name = "CC writer", isDaemon = true, priority = Thread.NORM_PRIORITY) {
            try {
                createOutputStream().use { outputStream ->
                    val outputChannel = Channels.newChannel(outputStream)
                    while (true) {
                        val buffer = ready.poll()
                        if (buffer == null) {
                            // give the producer another chance
                            Thread.yield()
                            continue
                        }
                        if (!buffer.hasRemaining()) {
                            /** producer is signaling end of stream
                             * see [QueuedOutputStream.close]
                             **/
                            break
                        }
                        try {
                            outputChannel.write(buffer)
                        } finally {
                            // always return the buffer
                            buffers.put(buffer)
                        }
                    }
                }
            } catch (e: Exception) {
                buffers.fail(e)
            } finally {
                // in case of failure, this releases some memory until the
                // producer realizes there was a failure
                ready.clear()
            }
            logger.debug {
                "${javaClass.name} writer ${Thread.currentThread()} finished."
            }
        }
        return QueuedOutputStream(buffers, ready) {
            writer.join()
        }
    }
}


/**
 * An [OutputStream] implementation that writes to buffers taken from a [ByteBufferPool]
 * and posts them to the given [ready] queue when they are full.
 */
private
class QueuedOutputStream(
    private val buffers: ByteBufferPool,
    private val ready: Queue<ByteBuffer>,
    private val onClose: () -> Unit,
) : OutputStream() {

    private
    var buffer = buffers.take()

    override fun write(b: ByteArray, off: Int, len: Int) {
        writeByteArray(b, off, len)
    }

    override fun write(b: Int) {
        buffer.put(b.toByte())
        maybeFlush()
    }

    override fun close() {
        // send remaining data
        if (buffer.position() > 0) {
            sendBuffer()
            takeNextBuffer()
        }
        // send a last empty buffer to signal the end
        sendBuffer()
        onClose()
        buffers.rethrowFailureIfAny()
        super.close()
    }

    private
    tailrec fun writeByteArray(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) {
            return
        }
        val remaining = buffer.remaining()
        if (remaining > len) {
            putByteArrayAndFlush(b, off, len)
        } else {
            putByteArrayAndFlush(b, off, remaining)
            writeByteArray(b, off + remaining, len - remaining)
        }
    }

    private
    fun putByteArrayAndFlush(b: ByteArray, off: Int, len: Int) {
        buffer.put(b, off, len)
        maybeFlush()
    }

    private
    fun maybeFlush() {
        if (!buffer.hasRemaining()) {
            sendBuffer()
            takeNextBuffer()
        }
    }

    private
    fun sendBuffer() {
        buffer.flip()
        ready.offer(buffer)
    }

    private
    fun takeNextBuffer() {
        buffer = buffers.take()
    }
}


/**
 * Manages a pool of buffers of fixed [capacity][ByteBufferPool.bufferCapacity] allocated on-demand
 * upto a [fixed maximum][ByteBufferPool.bufferCount].
 */
private
class ByteBufferPool {

    companion object {

        /**
         * How many bytes are transferred, at a time, from the producer to the writer thread.
         *
         * The smaller the number the more parallelism between producer and writer.
         * The default is `32` for increased parallelism.
         */
        val bufferCapacity = System.getProperty("org.gradle.configuration-cache.internal.buffer-capacity", null)?.toInt()
            ?: 32

        /**
         * Maximum number of buffers to be allocated.
         *
         * Determines the maximum memory working set: [bufferCount] * [bufferCapacity].
         * The default maximum working set is `32MB`.
         */
        val bufferCount = System.getProperty("org.gradle.configuration-cache.internal.buffer-count", null)?.toInt()
            ?: (1024 * 1024)

        val timeoutMinutes: Long = System.getProperty("org.gradle.configuration-cache.internal.buffer-timeout-minutes", null)?.toLong()
            ?: 30L /* stream can be kept open during the whole configuration phase */
    }

    private
    val buffers = ArrayBlockingQueue<ByteBuffer>(bufferCount)

    private
    var buffersToAllocate = buffers.remainingCapacity()

    private
    val failure = AtomicReference<Exception>(null)

    fun put(buffer: ByteBuffer) {
        buffer.flip()
        if (!buffers.offer(buffer, timeoutMinutes, TimeUnit.MINUTES)) {
            timeout()
        }
    }

    fun take(): ByteBuffer {
        rethrowFailureIfAny()
        if (buffersToAllocate > 0) {
            --buffersToAllocate
            return ByteBuffer.allocate(bufferCapacity)
        }
        return buffers.poll(timeoutMinutes, TimeUnit.MINUTES)
            ?: timeout()
    }

    fun fail(e: Exception) {
        failure.set(e)
    }

    fun rethrowFailureIfAny() {
        failure.get()?.let {
            throw it
        }
    }

    private
    fun timeout(): Nothing = throw TimeoutException("Writer thread timed out.")
}


