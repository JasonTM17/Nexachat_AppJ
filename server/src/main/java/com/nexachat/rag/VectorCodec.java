package com.nexachat.rag;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Float32 little-endian codec for stored embedding vectors. */
public final class VectorCodec {

    private VectorCodec() {
    }

    public static byte[] encode(float[] vector) {
        if (vector == null) {
            throw new IllegalArgumentException("vector must not be null");
        }
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    public static float[] decode(byte[] bytes, int dim) {
        if (bytes == null || bytes.length != dim * Float.BYTES) {
            throw new IllegalArgumentException("vector byte length does not match its dimension");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[dim];
        for (int index = 0; index < dim; index++) {
            vector[index] = buffer.getFloat();
        }
        return vector;
    }

    public static double cosine(float[] left, float[] right) {
        if (left == null || right == null || left.length != right.length || left.length == 0) {
            return 0.0;
        }
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int index = 0; index < left.length; index++) {
            dot += (double) left[index] * right[index];
            leftNorm += (double) left[index] * left[index];
            rightNorm += (double) right[index] * right[index];
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}
