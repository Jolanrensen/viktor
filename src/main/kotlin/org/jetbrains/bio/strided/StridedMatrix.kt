package org.jetbrains.bio.strided

/**
 * A strided matrix stored in a flat double array.
 *
 * @author Sergei Lebedev
 * @since 0.1.0
 */
object StridedMatrix {
    operator fun invoke(numRows: Int, numColumns: Int): StridedMatrix2 {
        return StridedMatrix2(numRows, numColumns)
    }

    operator inline fun invoke(numRows: Int, numColumns: Int,
                               block: (Int, Int) -> Double): StridedMatrix2 {
        val m = StridedMatrix2(numRows, numColumns)
        for (r in 0..numRows - 1) {
            for (c in 0..numColumns - 1) {
                m[r, c] = block(r, c)
            }
        }
        return m
    }

    operator fun invoke(numRows: Int, numColumns: Int, depth: Int): StridedMatrix3 {
        return StridedMatrix3(numRows, numColumns, depth)
    }

    operator inline fun invoke(depth: Int, numRows: Int, numColumns: Int,
                               block: (Int, Int, Int) -> Double): StridedMatrix3 {
        val m = StridedMatrix3(depth, numRows, numColumns)
        for (d in 0..depth - 1) {
            for (r in 0..numRows - 1) {
                for (c in 0..numColumns - 1) {
                    m[d, r, c] = block(d, r, c)
                }
            }
        }

        return m
    }

    @JvmStatic fun full(numRows: Int, numColumns: Int,
                        init: Double): StridedMatrix2 {
        val m = StridedMatrix2(numRows, numColumns)
        m.fill(init)
        return m
    }

    @JvmStatic fun full(numRows: Int, numColumns: Int, depth: Int,
                        init: Double): StridedMatrix3 {
        val m = StridedMatrix3(numRows, numColumns, depth)
        m.fill(init)
        return m
    }

    /**
     * Creates a matrix with rows summing to one.
     */
    fun stochastic(size: Int) = full(size, size, 1.0 / size)

    fun indexedStochastic(depth: Int, size: Int) = full(depth, size, size, 1.0 / size)
}