package space.bitos.app.ui.create.meme

/** Native adapter: shared row-major 4×5 byte colors → GL column-major RGB + bias. */
internal object MemeVideoColor {
    fun glMatrix(matrix: FloatArray): FloatArray {
        require(matrix.size == 20)
        return floatArrayOf(
            matrix[0], matrix[5], matrix[10], 0f,
            matrix[1], matrix[6], matrix[11], 0f,
            matrix[2], matrix[7], matrix[12], 0f,
            matrix[4] / 255f, matrix[9] / 255f, matrix[14] / 255f, 1f,
        )
    }
}
