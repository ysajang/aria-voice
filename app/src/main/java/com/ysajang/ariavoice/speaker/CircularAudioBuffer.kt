package com.ysajang.ariavoice.speaker

/**
 * Thread-safe circular buffer for collecting recent audio samples.
 *
 * Used to capture ~1.5 sec of audio around wake word detection
 * for speaker verification.
 *
 * @param capacity  Max samples to store (default: 32000 = 2s @ 16kHz).
 */
class CircularAudioBuffer(private val capacity: Int = 32_000) {

    private val buffer = FloatArray(capacity)
    private var writePos = 0
    private var count = 0
    private val lock = Any()

    /**
     * Append audio chunk to buffer.
     * Called from AudioRecorder's flow callback.
     */
    fun write(chunk: FloatArray) {
        synchronized(lock) {
            for (sample in chunk) {
                buffer[writePos] = sample
                writePos = (writePos + 1) % capacity
            }
            count = minOf(count + chunk.size, capacity)
        }
    }

    /**
     * Get snapshot of all stored audio (oldest → newest).
     * Returns a copy — safe to use after further writes.
     */
    fun snapshot(): FloatArray {
        synchronized(lock) {
            if (count == 0) return FloatArray(0)

            val result = FloatArray(count)
            if (count < capacity) {
                // Buffer not yet full: data starts at 0
                val start = writePos - count
                if (start >= 0) {
                    System.arraycopy(buffer, start, result, 0, count)
                } else {
                    val wrapLen = -start
                    System.arraycopy(buffer, capacity + start, result, 0, wrapLen)
                    System.arraycopy(buffer, 0, result, wrapLen, count - wrapLen)
                }
            } else {
                // Buffer full: writePos is the oldest position
                val firstLen = capacity - writePos
                System.arraycopy(buffer, writePos, result, 0, firstLen)
                System.arraycopy(buffer, 0, result, firstLen, writePos)
            }
            return result
        }
    }

    /** Number of valid samples currently stored. */
    fun size(): Int = synchronized(lock) { count }

    /** Clear the buffer. */
    fun clear() {
        synchronized(lock) {
            writePos = 0
            count = 0
        }
    }
}
