package org.jetbrains.bio.viktor

import org.apache.commons.math3.util.FastMath
import org.apache.commons.math3.util.Precision
import java.text.DecimalFormat
import kotlin.math.ln
import kotlin.math.ln1p

/**
 * An 1-dimensional specialization of [F64Array].
 *
 * @since 0.4.0
 */
open class F64FlatArray protected constructor(
    data: DoubleArray,
    offset: Int,
    stride: Int,
    size: Int
) : F64Array(data, offset, intArrayOf(stride), intArrayOf(size), 1, stride, size) {

    internal open val unsafeGet: (Int) -> Double = { data[it * stride + offset] }
    internal open val unsafeSet: (Int, Double) -> Unit = { i, v -> data[i * stride + offset] = v }

    override operator fun get(pos: Int): Double {
        checkIndex("pos", pos, size)
        return unsafeGet(pos)
    }

    override operator fun set(pos: Int, value: Double) {
        checkIndex("pos", pos, size)
        unsafeSet(pos, value)
    }

    override fun flatten() = this

    override fun contains(other: Double): Boolean {
        for (pos in 0 until size) {
            if (unsafeGet(pos) == other) {
                return true
            }
        }

        return false
    }

    override fun along(axis: Int) = unsupported()

    override fun view(index: Int, axis: Int) = unsupported()

    override fun copyTo(other: F64Array) {
        val o = checkShape(other)
        for (pos in 0 until size) {
            o.unsafeSet(pos, unsafeGet(pos))
        }
    }

    override fun copy(): F64FlatArray = F64DenseFlatArray.create(toDoubleArray(), 0, size)

    override fun fill(init: Double) {
        for (pos in 0 until size) {
            unsafeSet(pos, init)
        }
    }

    override fun reorder(indices: IntArray, axis: Int) {
        if (axis == 0) {
            reorderInternal(this, indices, axis,
                get = { pos -> unsafeGet(pos) },
                set = { pos, value -> unsafeSet(pos, value) })
        } else {
            unsupported()
        }
    }

    override fun dot(other: ShortArray) = balancedSum { unsafeGet(it) * other[it].toDouble() }

    override fun dot(other: IntArray) = balancedSum { unsafeGet(it) * other[it].toDouble() }

    override fun dot(other: F64Array) = balancedSum { unsafeGet(it) * other[it] }

    /**
     * Summation algorithm balancing accuracy with throughput.
     *
     * References
     * ----------
     *
     * Dalton et al. "SIMDizing pairwise sums", 2014.
     */
    private inline fun balancedSum(getter: (Int) -> Double): Double {
        var accUnaligned = 0.0
        var remaining = size
        while (remaining % 4 > 0) {
            remaining--
            accUnaligned += getter(remaining)
        }
        val stack = DoubleArray(31 - 2)
        var p = 0
        var i = 0
        while (i < remaining) {
            // Shift.
            var v = getter(i) + getter(i + 1)
            val w = getter(i + 2) + getter(i + 3)
            v += w

            // Reduce.
            var bitmask = 4
            while (i and bitmask != 0) {
                v += stack[--p]
                bitmask = bitmask shl 1
            }
            stack[p++] = v
            i += 4
        }
        var acc = 0.0
        while (p > 0) {
            acc += stack[--p]
        }
        return acc + accUnaligned
    }

    override fun sum(): Double = balancedSum { unsafeGet(it) }

    override fun cumSum() {
        val acc = KahanSum()
        for (pos in 0 until size) {
            acc += unsafeGet(pos)
            unsafeSet(pos, acc.result())
        }
    }

    override fun min() = unsafeGet(argMin())

    override fun argMin(): Int {
        var minValue = Double.POSITIVE_INFINITY
        var res = 0
        for (pos in 0 until size) {
            val value = unsafeGet(pos)
            if (value <= minValue) {
                minValue = value
                res = pos
            }
        }
        return res
    }

    override fun max() = unsafeGet(argMax())

    override fun argMax(): Int {
        var maxValue = Double.NEGATIVE_INFINITY
        var res = 0
        for (pos in 0 until size) {
            val value = unsafeGet(pos)
            if (value >= maxValue) {
                maxValue = value
                res = pos
            }
        }
        return res
    }

    private inline fun flatTransformInPlace(op: (Double) -> Double) {
        for (pos in 0 until size) {
            unsafeSet(pos, op.invoke(unsafeGet(pos)))
        }
    }

    private inline fun flatTransform(op: (Double) -> Double): F64FlatArray {
        val res = DoubleArray(size)
        for (pos in 0 until size) {
            res[pos] = op.invoke(unsafeGet(pos))
        }
        return create(res)
    }

    private inline fun flatEBEInPlace(other: F64Array, op: (Double, Double) -> Double) {
        val o = checkShape(other)
        for (pos in 0 until size) {
            unsafeSet(pos, op.invoke(unsafeGet(pos), o.unsafeGet(pos)))
        }
    }

    private inline fun flatEBE(other: F64Array, op: (Double, Double) -> Double): F64FlatArray {
        val o = checkShape(other)
        val res = DoubleArray(size)
        for (pos in 0 until size) {
            res[pos] = op.invoke(unsafeGet(pos), o.unsafeGet(pos))
        }
        return F64DenseFlatArray.create(res, 0, size)
    }

    override fun transformInPlace(op: (Double) -> Double) = flatTransformInPlace(op)

    override fun transform(op: (Double) -> Double): F64FlatArray = flatTransform(op)

    override fun <T> fold(initial: T, op: (T, Double) -> T): T {
        var res = initial
        for (pos in 0 until size) {
                res = op(res, unsafeGet(pos))
            }
        return res
    }

    override fun reduce(op: (Double, Double) -> Double): Double {
        var res = unsafeGet(0)
        for (pos in 1 until size) {
            res = op(res, unsafeGet(pos))
        }
        return res
    }

    /* Mathematics */

    // FastMath is faster with exp and expm1, but slower with log and log1p
    // (confirmed by benchmarks on several JDK and hardware combinations)

    override fun exp() = transform(FastMath::exp)

    override fun expm1() = transform(FastMath::expm1)

    override fun log() = transform(::ln)

    override fun log1p() = transform(::ln1p)

    override fun logSumExp(): Double {
        val offset = max()
        val acc = KahanSum()
        for (pos in 0 until size) {
            acc += FastMath.exp(unsafeGet(pos) - offset)
        }
        return ln(acc.result()) + offset
    }

    override fun logAddExpAssign(other: F64Array) = flatEBEInPlace(other) { a, b -> a logAddExp b }

    override fun logAddExp(other: F64Array): F64FlatArray = flatEBE(other) { a, b -> a logAddExp b }

    /* Arithmetic */

    override fun plusAssign(other: F64Array) = flatEBEInPlace(other) { a, b -> a + b }

    override fun plus(other: F64Array): F64FlatArray = flatEBE(other) { a, b -> a + b }

    override fun minusAssign(other: F64Array) = flatEBEInPlace(other) { a, b -> a - b }

    override fun minus(other: F64Array): F64FlatArray = flatEBE(other) { a, b -> a - b }

    override fun timesAssign(other: F64Array) = flatEBEInPlace(other) { a, b -> a * b }

    override fun times(other: F64Array): F64FlatArray = flatEBE(other) { a, b -> a * b }

    override fun divAssign(other: F64Array) = flatEBEInPlace(other) { a, b -> a / b }

    override fun div(other: F64Array): F64FlatArray = flatEBE(other) { a, b -> a / b }

    protected fun checkShape(other: F64Array): F64FlatArray {
        check(this === other || (other is F64FlatArray && shape[0] == other.shape[0])) {
            "operands shapes do not match: ${shape.contentToString()} vs ${other.shape.contentToString()}"
        }
        return other as F64FlatArray
    }

    override fun reshape(vararg shape: Int): F64Array {
        shape.forEach { require(it > 0) { "shape must be positive but was $it" } }
        check(shape.product() == size) { "total size of the new array must be unchanged" }
        return when {
            this.shape.contentEquals(shape) -> this
            else -> {
                val reshaped = shape.clone()
                reshaped[reshaped.lastIndex] = strides.single()
                for (i in reshaped.lastIndex - 1 downTo 0) {
                    reshaped[i] = reshaped[i + 1] * shape[i + 1]
                }
                create(data, offset, reshaped, shape)
            }
        }
    }

    override fun asSequence(): Sequence<Double> = (0 until size).asSequence().map { unsafeGet(it) }

    override fun clone(): F64FlatArray = F64FlatArray(data.clone(), offset, strides[0], shape[0])

    override fun toArray() = toDoubleArray()

    override fun toGenericArray() = unsupported()

    override fun toDoubleArray() = DoubleArray(size) { unsafeGet(it) }

    /**
     * A version of [DecimalFormat.format] which doesn't produce ?
     * for [Double.NaN] and infinities.
     */
    private fun DecimalFormat.safeFormat(value: Double) = when {
        value.isNaN() -> "nan"
        value == Double.POSITIVE_INFINITY -> "inf"
        value == Double.NEGATIVE_INFINITY -> "-inf"
        else -> format(value)
    }

    override fun toString(maxDisplay: Int, format: DecimalFormat): String {
        val sb = StringBuilder()
        sb.append('[')

        if (maxDisplay < size) {
            for (pos in 0 until maxDisplay / 2) {
                sb.append(format.safeFormat(this[pos])).append(", ")
            }

            sb.append("..., ")

            val leftover = maxDisplay - maxDisplay / 2
            for (pos in size - leftover until size) {
                sb.append(format.safeFormat(this[pos]))
                if (pos < size - 1) {
                    sb.append(", ")
                }
            }
        } else {
            for (pos in 0 until size) {
                sb.append(format.safeFormat(this[pos]))
                if (pos < size - 1) {
                    sb.append(", ")
                }
            }
        }

        sb.append(']')
        return sb.toString()
    }

    override fun equals(other: Any?) = when {
        this === other -> true
        other !is F64FlatArray -> false // an instance of F64Array can't be flat
        size != other.size -> false
        else -> (0 until size).all {
            Precision.equals(unsafeGet(it), other.unsafeGet(it))
        }
    }

    override fun hashCode() = (0 until size).fold(1) { acc, pos ->
        // XXX calling #hashCode results in boxing, see KT-7571.
        31 * acc + java.lang.Double.hashCode(unsafeGet(pos))
    }

    companion object {
        internal fun create(
            data: DoubleArray,
            offset: Int = 0,
            stride: Int = 1,
            size: Int = data.size
        ): F64FlatArray {
            // require(offset + (size - 1) * stride < data.size) { "not enough data" }
            // this check is not needed since we control all invocations of this internal method
            require(size > 0) { "empty arrays not supported" }
            return if (stride == 1) {
                F64DenseFlatArray.create(data, offset, size)
            } else {
                F64FlatArray(data, offset, stride, size)
            }
        }
    }
}
