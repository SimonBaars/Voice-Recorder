package org.fossify.voicerecorder.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import org.fossify.voicerecorder.extensions.config
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

// Split complex method into smaller focused methods for better maintainability
@Suppress("TooManyFunctions")
class WavRecorder(val context: Context) : Recorder {
    private var audioRecord: AudioRecord? = null
    private var recordFile: File? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var bufferSize = 0
    private var isPaused = AtomicBoolean(false)
    private var isStopped = AtomicBoolean(false)
    private var isRecording = AtomicBoolean(false)
    private var amplitude = AtomicInteger(0)
    private var recordingThread: Thread? = null

    companion object {
        private const val RECORDER_BPP = 16 // bits per sample
        private const val WAV_HEADER_SIZE = 44
        private const val WAV_CHUNK_ID_OFFSET = 36
        private const val AMPLITUDE_DIVISOR = 16
        private const val BYTES_PER_SAMPLE = 2
        private const val MONO_CHANNELS = 1
        private const val BITS_PER_BYTE = 8
    }

    override fun setOutputFile(path: String) {
        recordFile = File(path)
    }

    override fun setOutputFile(parcelFileDescriptor: ParcelFileDescriptor) {
        fileDescriptor = ParcelFileDescriptor.dup(parcelFileDescriptor.fileDescriptor)
    }

    override fun prepare() {
        val sampleRate = context.config.samplingRate
        val channelConfig = AudioFormat.CHANNEL_IN_MONO

        bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        )

        check(bufferSize != AudioRecord.ERROR && bufferSize != AudioRecord.ERROR_BAD_VALUE) {
            "Invalid buffer size"
        }

        @SuppressLint("MissingPermission")
        audioRecord = AudioRecord(
            context.config.microphoneMode,
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * BYTES_PER_SAMPLE
        )

        check(audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord initialization failed"
        }
    }

    override fun start() {
        audioRecord?.startRecording()
        isRecording.set(true)
        isStopped.set(false)
        isPaused.set(false)

        recordingThread = Thread({
            writeAudioDataToFile()
        }, "WavRecorder Thread")
        recordingThread?.start()
    }

    override fun stop() {
        isStopped.set(true)
        isRecording.set(false)
        audioRecord?.stop()
    }

    override fun pause() {
        isPaused.set(true)
    }

    override fun resume() {
        isPaused.set(false)
    }

    override fun release() {
        audioRecord?.release()
        audioRecord = null
        fileDescriptor?.close()
        fileDescriptor = null
    }

    override fun getMaxAmplitude(): Int {
        return amplitude.get()
    }

    @Suppress("NestedBlockDepth", "ComplexMethod")
    private fun writeAudioDataToFile() {
        val fos = openOutputStream() ?: return
        
        writeEmptyHeader(fos)
        recordAudioData(fos)
        updateWavHeader()
        closeOutputStream(fos)
    }

    private fun openOutputStream(): FileOutputStream? {
        return try {
            if (fileDescriptor != null) {
                FileOutputStream(fileDescriptor!!.fileDescriptor)
            } else {
                FileOutputStream(recordFile!!)
            }
        } catch (e: FileNotFoundException) {
            null
        }
    }

    private fun recordAudioData(fos: FileOutputStream) {
        val data = ByteArray(bufferSize)
        val shortBuffer = ByteBuffer.allocate(BYTES_PER_SAMPLE)
        shortBuffer.order(ByteOrder.LITTLE_ENDIAN)

        while (isRecording.get()) {
            if (!isPaused.get()) {
                val bytesRead = audioRecord?.read(data, 0, bufferSize) ?: 0
                if (bytesRead > 0) {
                    calculateAmplitude(data, bytesRead, shortBuffer)
                    writeAudioData(fos, data, bytesRead)
                }
            }
        }
    }

    private fun calculateAmplitude(data: ByteArray, bytesRead: Int, shortBuffer: ByteBuffer) {
        var sum = 0L
        var i = 0
        while (i + 1 < bytesRead) {
            shortBuffer.put(data[i])
            shortBuffer.put(data[i + 1])
            sum += abs(shortBuffer.getShort(0).toInt())
            shortBuffer.clear()
            i += BYTES_PER_SAMPLE
        }
        if (bytesRead > 0) {
            amplitude.set((sum / (bytesRead / AMPLITUDE_DIVISOR)).toInt())
        }
    }

    private fun writeAudioData(fos: FileOutputStream, data: ByteArray, bytesRead: Int) {
        try {
            fos.write(data, 0, bytesRead)
        } catch (ignored: IOException) {
        }
    }

    private fun updateWavHeader() {
        try {
            val totalBytesWritten = getTotalBytesWritten()
            val fileSize = totalBytesWritten - WAV_HEADER_SIZE
            val totalSize = fileSize + WAV_CHUNK_ID_OFFSET
            val sampleRate = context.config.samplingRate.toLong()
            val bytesPerSample = RECORDER_BPP / BITS_PER_BYTE
            val byteRate = sampleRate * MONO_CHANNELS * bytesPerSample
            
            val headerBytes = generateHeader(fileSize, totalSize, sampleRate, MONO_CHANNELS, byteRate)
            writeHeaderToFile(headerBytes)
        } catch (ignored: ErrnoException) {
        } catch (ignored: IOException) {
        }
    }

    private fun getTotalBytesWritten(): Long {
        return if (fileDescriptor != null) {
            try {
                android.system.Os.lseek(
                    fileDescriptor!!.fileDescriptor,
                    0,
                    android.system.OsConstants.SEEK_CUR
                )
            } catch (ignored: ErrnoException) {
                0
            }
        } else {
            recordFile?.length() ?: 0
        }
    }

    private fun writeHeaderToFile(headerBytes: ByteArray) {
        if (fileDescriptor != null) {
            writeHeaderToDescriptor(headerBytes)
        } else if (recordFile != null) {
            writeHeaderToRandomAccessFile(headerBytes)
        }
    }

    private fun writeHeaderToDescriptor(headerBytes: ByteArray) {
        try {
            android.system.Os.lseek(
                fileDescriptor!!.fileDescriptor,
                0,
                android.system.OsConstants.SEEK_SET
            )
            android.system.Os.write(
                fileDescriptor!!.fileDescriptor,
                headerBytes,
                0,
                headerBytes.size
            )
        } catch (ignored: ErrnoException) {
        }
    }

    private fun writeHeaderToRandomAccessFile(headerBytes: ByteArray) {
        try {
            val raf = RandomAccessFile(recordFile!!, "rw")
            raf.seek(0)
            raf.write(headerBytes)
            raf.close()
        } catch (ignored: IOException) {
        }
    }

    private fun closeOutputStream(fos: FileOutputStream) {
        try {
            fos.flush()
            fos.close()
        } catch (ignored: IOException) {
        }
    }

    private fun writeEmptyHeader(fos: FileOutputStream) {
        try {
            val header = ByteArray(WAV_HEADER_SIZE)
            fos.write(header)
            fos.flush()
        } catch (ignored: IOException) {
        }
    }

    @Suppress("MagicNumber")
    private fun generateHeader(
        totalAudioLen: Long,
        totalDataLen: Long,
        longSampleRate: Long,
        channels: Int,
        byteRate: Long
    ): ByteArray {
        val header = ByteArray(44)

        header[0] = 'R'.code.toByte()  // RIFF/WAVE header
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()  // 'fmt ' chunk
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16  // 16 for PCM
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1  // format = 1 (PCM)
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (longSampleRate and 0xff).toByte()
        header[25] = ((longSampleRate shr 8) and 0xff).toByte()
        header[26] = ((longSampleRate shr 16) and 0xff).toByte()
        header[27] = ((longSampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * (RECORDER_BPP / 8)).toByte()  // block align
        header[33] = 0
        header[34] = RECORDER_BPP.toByte()  // bits per sample
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        return header
    }
}
