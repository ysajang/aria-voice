package com.ysajang.ariavoice.speaker

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Thread-safe circular buffer for raw PCM audio (FloatArray).
 * Stores the last [capacitySamples] samples continuously written by AudioRecorder.
 *
 * @param capacitySamples  Total sample capacity (e.g. 16000 * 2.5f = 40000 for 2.5 sec at 16kHz)
 */
class CircularAudioBuffer(private val capacitySamples: Int) {

    private val buffer   = FloatArray(capacitySamples)
    private var writePos = 0
    private var filled   = 0          // how many valid samples are stored
    private val lock     = ReentrantLock()

    /** Write [samples] into the buffer. Overwrites oldest data if full. */
    fun write(samples: FloatArray) {
        if (samples.isEmpty()) return
        lock.withLock {
            val n = samples.size
            if (n >= capacitySamples) {
                // New data larger than buffer – keep only the last capacitySamples
                samples.copyInto(buffer, 0, n - capacitySamples, n)
                writePos = 0
                filled   = capacitySamples
                return
            }
            val available = capacitySamples - writePos
            if (n <= available) {
                samples.copyInto(buffer, writePos)
                writePos = (writePos + n) % capacitySamples
            } else {
                // Wrap around
                samples.copyInto(buffer, writePos, 0, available)
                val remainder = n - available
                samples.copyInto(buffer, 0, available, n)
                writePos = remainder
            }
            filled = minOf(filled + n, capacitySamples)
        }
    }

    /**
     * Returns the most recent [n] samples (or fewer if not enough data yet).
     * Result is in chronological order (oldest first).
     */
    fun readLast(n: Int): FloatArray {
        lock.withLock {
            val count  = minOf(n, filled)
            val result = FloatArray(count)
            // startPos is the oldest sample we want
            val startPos = ((writePos - count) + capacitySamples) % capacitySamples
            val firstChunk = capacitySamples - startPos
            if (firstChunk >= count) {
                buffer.copyInto(result, 0, startPos, startPos + count)
            } else {
                buffer.copyInto(result, 0,          startPos, startPos + firstChunk)
                buffer.copyInto(result, firstChunk, 0,        count - firstChunk)
            }
            return result
        }
    }

    /** Number of valid samples currently stored. */
    fun size(): Int = lock.withLock { filled }

    fun clear() {
        lock.withLock {
            writePos = 0
            filled   = 0
            buffer.fill(0f)
        }
    }
}
