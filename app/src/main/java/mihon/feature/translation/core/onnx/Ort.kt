package mihon.feature.translation.core.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Small helpers around ONNX Runtime so the model wrappers stay readable.
 */
object Ort {
    val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment().apply { setTelemetry(false) } }

    fun createSession(model: File, threads: Int = defaultThreads()): OrtSession {
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setMemoryPatternOptimization(true)
        }
        return env.createSession(model.absolutePath, options)
    }

    fun defaultThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    fun floatBuffer(size: Int): FloatBuffer =
        ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun longBuffer(size: Int): LongBuffer =
        ByteBuffer.allocateDirect(size * 8).order(ByteOrder.nativeOrder()).asLongBuffer()

    fun floatTensor(buffer: FloatBuffer, vararg shape: Long): OnnxTensor {
        buffer.rewind()
        return OnnxTensor.createTensor(env, buffer, shape)
    }

    fun floatTensor(data: FloatArray, vararg shape: Long): OnnxTensor {
        val buffer = floatBuffer(data.size)
        buffer.put(data)
        return floatTensor(buffer, *shape)
    }

    fun longTensor(data: LongArray, vararg shape: Long): OnnxTensor {
        val buffer = longBuffer(data.size)
        buffer.put(data)
        buffer.rewind()
        return OnnxTensor.createTensor(env, buffer, shape)
    }

    fun boolTensor(value: Boolean): OnnxTensor = OnnxTensor.createTensor(env, booleanArrayOf(value))

    /** Shape of a session input/output; unknown (symbolic) dimensions are reported as -1. */
    fun shapeOf(session: OrtSession, name: String, input: Boolean): LongArray? {
        val node = if (input) session.inputInfo[name] else session.outputInfo[name]
        return (node?.info as? TensorInfo)?.shape
    }

    fun OrtSession.Result.tensor(index: Int): OnnxTensor = get(index) as OnnxTensor

    fun OrtSession.Result.tensor(name: String): OnnxTensor? = get(name).orElse(null) as? OnnxTensor

    val OnnxValue.shape: LongArray get() = (info as TensorInfo).shape

    fun argmax(buffer: FloatBuffer, offset: Int, length: Int, skip: Int = -1): Int {
        var best = -1
        var bestValue = Float.NEGATIVE_INFINITY
        for (i in 0 until length) {
            if (i == skip) continue
            val v = buffer.get(offset + i)
            if (v > bestValue) {
                bestValue = v
                best = i
            }
        }
        return best
    }
}
