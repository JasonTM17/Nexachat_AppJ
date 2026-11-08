package com.nexachat.core.media;

import androidx.annotation.NonNull;

/** Pure Java BlurHash encoder for packed ARGB pixels. */
public final class BlurHashEncoder {

    private static final String BASE83 =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~";

    private BlurHashEncoder() { }

    @NonNull
    public static String encode(
            int width,
            int height,
            @NonNull int[] argb,
            int componentsX,
            int componentsY) {
        if (width <= 0 || height <= 0 || (long) width * height != argb.length) {
            throw new IllegalArgumentException("pixel dimensions do not match");
        }
        if (componentsX < 1 || componentsX > 9 || componentsY < 1 || componentsY > 9) {
            throw new IllegalArgumentException("component count must be between 1 and 9");
        }

        double[][] factors = new double[componentsX * componentsY][3];
        for (int yComponent = 0; yComponent < componentsY; yComponent++) {
            for (int xComponent = 0; xComponent < componentsX; xComponent++) {
                int factorIndex = xComponent + yComponent * componentsX;
                factors[factorIndex] = encodeFactor(
                        width, height, argb, xComponent, yComponent);
            }
        }

        StringBuilder hash = new StringBuilder(4 + 2 * factors.length);
        int sizeFlag = (componentsX - 1) + (componentsY - 1) * 9;
        hash.append(encode83(sizeFlag, 1));

        double maximumValue = 0.0;
        for (int index = 1; index < factors.length; index++) {
            for (double channel : factors[index]) {
                maximumValue = Math.max(maximumValue, Math.abs(channel));
            }
        }
        int quantizedMaximumValue = Math.max(0,
                Math.min(82, (int) Math.floor(maximumValue * 166.0 - 0.5)));
        double actualMaximumValue = (quantizedMaximumValue + 1) / 166.0;
        hash.append(encode83(quantizedMaximumValue, 1));
        hash.append(encode83(encodeDc(factors[0]), 4));
        for (int index = 1; index < factors.length; index++) {
            hash.append(encode83(encodeAc(factors[index], actualMaximumValue), 2));
        }
        return hash.toString();
    }

    private static double[] encodeFactor(
            int width,
            int height,
            int[] argb,
            int xComponent,
            int yComponent) {
        double red = 0.0;
        double green = 0.0;
        double blue = 0.0;
        double normalization = xComponent == 0 && yComponent == 0 ? 1.0 : 2.0;
        for (int y = 0; y < height; y++) {
            double basisY = Math.cos(Math.PI * yComponent * y / height);
            for (int x = 0; x < width; x++) {
                double basis = normalization
                        * Math.cos(Math.PI * xComponent * x / width)
                        * basisY;
                int pixel = argb[y * width + x];
                red += basis * srgbToLinear((pixel >>> 16) & 0xff);
                green += basis * srgbToLinear((pixel >>> 8) & 0xff);
                blue += basis * srgbToLinear(pixel & 0xff);
            }
        }
        double pixelCount = (double) width * height;
        return new double[] {red / pixelCount, green / pixelCount, blue / pixelCount};
    }

    private static int encodeDc(double[] value) {
        return linearToSrgb(value[0]) << 16
                | linearToSrgb(value[1]) << 8
                | linearToSrgb(value[2]);
    }

    private static int encodeAc(double[] value, double maximumValue) {
        int red = quantizeAc(value[0], maximumValue);
        int green = quantizeAc(value[1], maximumValue);
        int blue = quantizeAc(value[2], maximumValue);
        return red * 19 * 19 + green * 19 + blue;
    }

    private static int quantizeAc(double value, double maximumValue) {
        double signedRoot = Math.copySign(Math.sqrt(Math.abs(value) / maximumValue), value);
        return (int) Math.floor(Math.max(0.0, Math.min(18.0, signedRoot * 9.0 + 9.5)));
    }

    private static int linearToSrgb(double value) {
        double srgb = value <= 0.0031308
                ? value * 12.92
                : 1.055 * Math.pow(value, 1.0 / 2.4) - 0.055;
        return clamp((int) Math.round(srgb * 255.0), 0, 255);
    }

    private static double srgbToLinear(int value) {
        double srgb = value / 255.0;
        return srgb <= 0.04045
                ? srgb / 12.92
                : Math.pow((srgb + 0.055) / 1.055, 2.4);
    }

    private static String encode83(int value, int length) {
        char[] encoded = new char[length];
        for (int index = 1; index <= length; index++) {
            int digit = (int) (value / Math.pow(83.0, length - index)) % 83;
            encoded[index - 1] = BASE83.charAt(digit);
        }
        return new String(encoded);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
