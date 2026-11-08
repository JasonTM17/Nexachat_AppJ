package com.nexachat.core.media;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Pure Java BlurHash decoder returning opaque packed ARGB pixels. */
public final class BlurHashDecoder {

    private static final String BASE83 =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~";

    private BlurHashDecoder() { }

    @NonNull
    public static int[] decode(@NonNull String hash, int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("output dimensions must be positive");
        }
        int[] layout = validate(hash);
        int componentsX = layout[0];
        int componentsY = layout[1];
        double maximumValue = (decode83(hash, 1, 2) + 1) / 166.0;
        double[][] factors = new double[componentsX * componentsY][3];
        factors[0] = decodeDc(decode83(hash, 2, 6));
        for (int index = 1; index < factors.length; index++) {
            int encodedFactor = decode83(hash, 4 + index * 2, 6 + index * 2);
            if (encodedFactor >= 19 * 19 * 19) {
                throw new IllegalArgumentException("BlurHash AC component is out of range");
            }
            factors[index] = decodeAc(encodedFactor, maximumValue);
        }

        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double red = 0.0;
                double green = 0.0;
                double blue = 0.0;
                for (int yComponent = 0; yComponent < componentsY; yComponent++) {
                    double basisY = Math.cos(Math.PI * yComponent * y / height);
                    for (int xComponent = 0; xComponent < componentsX; xComponent++) {
                        double basis = Math.cos(Math.PI * xComponent * x / width) * basisY;
                        double[] factor = factors[xComponent + yComponent * componentsX];
                        red += factor[0] * basis;
                        green += factor[1] * basis;
                        blue += factor[2] * basis;
                    }
                }
                pixels[y * width + x] = 0xff000000
                        | linearToSrgb(red) << 16
                        | linearToSrgb(green) << 8
                        | linearToSrgb(blue);
            }
        }
        return pixels;
    }

    public static boolean isValid(@Nullable String hash) {
        if (hash == null) {
            return false;
        }
        try {
            validate(hash);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static int[] validate(String hash) {
        if (hash.length() < 6 || hash.length() > 64) {
            throw new IllegalArgumentException("BlurHash length is outside the supported range");
        }
        for (int index = 0; index < hash.length(); index++) {
            if (BASE83.indexOf(hash.charAt(index)) < 0) {
                throw new IllegalArgumentException("BlurHash contains invalid characters");
            }
        }
        int sizeFlag = decode83(hash, 0, 1);
        if (sizeFlag > 80) {
            throw new IllegalArgumentException("BlurHash component flag is out of range");
        }
        int componentsX = sizeFlag % 9 + 1;
        int componentsY = sizeFlag / 9 + 1;
        int expectedLength = 4 + 2 * componentsX * componentsY;
        if (hash.length() != expectedLength) {
            throw new IllegalArgumentException("BlurHash length does not match components");
        }
        for (int index = 1; index < componentsX * componentsY; index++) {
            int encodedFactor = decode83(hash, 4 + index * 2, 6 + index * 2);
            if (encodedFactor >= 19 * 19 * 19) {
                throw new IllegalArgumentException("BlurHash AC component is out of range");
            }
        }
        return new int[] {componentsX, componentsY};
    }

    private static double[] decodeDc(int value) {
        int red = value >> 16;
        int green = (value >> 8) & 0xff;
        int blue = value & 0xff;
        return new double[] {srgbToLinear(red), srgbToLinear(green), srgbToLinear(blue)};
    }

    private static double[] decodeAc(int value, double maximumValue) {
        int quantizedRed = value / (19 * 19);
        int quantizedGreen = (value / 19) % 19;
        int quantizedBlue = value % 19;
        return new double[] {
            signedSquare((quantizedRed - 9) / 9.0) * maximumValue,
            signedSquare((quantizedGreen - 9) / 9.0) * maximumValue,
            signedSquare((quantizedBlue - 9) / 9.0) * maximumValue
        };
    }

    private static double signedSquare(double value) {
        return Math.copySign(value * value, value);
    }

    private static int decode83(String hash, int start, int end) {
        int value = 0;
        for (int index = start; index < end; index++) {
            value = value * 83 + BASE83.indexOf(hash.charAt(index));
        }
        return value;
    }

    private static int linearToSrgb(double value) {
        double srgb = value <= 0.0031308
                ? value * 12.92
                : 1.055 * Math.pow(value, 1.0 / 2.4) - 0.055;
        return Math.max(0, Math.min(255, (int) Math.round(srgb * 255.0)));
    }

    private static double srgbToLinear(int value) {
        double srgb = value / 255.0;
        return srgb <= 0.04045
                ? srgb / 12.92
                : Math.pow((srgb + 0.055) / 1.055, 2.4);
    }
}
