package org.schabi.newpipe.util;

import android.graphics.Bitmap;
import android.graphics.Canvas;

import androidx.annotation.NonNull;

import eightbitlab.com.blurview.BlurAlgorithm;
import eightbitlab.com.blurview.BlurController;

/**
 * Y-SYNC: a bitmap-path {@link BlurAlgorithm} for the floating bars.
 *
 * <p>BlurView's own RenderEffectBlur renders through a RenderNode
 * ({@code canvas.drawRenderNode}), whose playback comes out empty or
 * garbage on some Mali GPUs — the bars then look solid (or worse,
 * flash corrupt frames) even though the blur setup is correct. This
 * algorithm stays on the plain bitmap path instead: the controller
 * hands over its downscaled capture, {@link #blur(Bitmap, float)}
 * blurs it in software, and {@link #render(Canvas, Bitmap)} draws it
 * with a regular {@code drawBitmap}, which every GPU composites
 * reliably.
 *
 * <p>The blur itself is an iterative box blur: three horizontal +
 * vertical box passes approximate a Gaussian. It works in place on the
 * given bitmap, keeps no state between calls (all buffers are
 * per-call), and honours the radius passed by the controller, clamped
 * to the range the sliding window supports.
 */
public final class StackBlurAlgorithm implements BlurAlgorithm {

    // Three box blur passes approximate a Gaussian blur.
    private static final int BOX_PASSES = 3;

    // Upper bound for the sliding window radius.
    private static final int MAX_RADIUS = 254;

    @Override
    public Bitmap blur(@NonNull final Bitmap bitmap, final float blurRadius) {
        final int width = bitmap.getWidth();
        final int height = bitmap.getHeight();
        final int radius = Math.max(1, Math.min(MAX_RADIUS, Math.round(blurRadius)));
        final int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        final int[] scratch = new int[pixels.length];
        for (int pass = 0; pass < BOX_PASSES; pass++) {
            boxBlurHorizontal(pixels, scratch, width, height, radius);
            boxBlurVertical(scratch, pixels, width, height, radius);
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }

    @Override
    public void destroy() {
        // No resources are held between calls: all buffers are per-call.
    }

    @Override
    public boolean canModifyBitmap() {
        // blur() blurs the given bitmap in place and returns it.
        return true;
    }

    @NonNull
    @Override
    public Bitmap.Config getSupportedBitmapConfig() {
        return Bitmap.Config.ARGB_8888;
    }

    @Override
    public float scaleFactor() {
        // Same downscaling the RenderEffectBlur setup used: the capture
        // bitmap stays small, which keeps the software blur cheap and
        // the result smooth.
        return BlurController.DEFAULT_SCALE_FACTOR;
    }

    @Override
    public void render(@NonNull final Canvas canvas, @NonNull final Bitmap bitmap) {
        // The controller calls this with the canvas already scaled so
        // the downscaled bitmap fills the BlurView: a plain bitmap draw
        // at the origin is the whole job — no RenderNode involved.
        canvas.drawBitmap(bitmap, 0f, 0f, null);
    }

    /**
     * One horizontal box blur pass with a sliding window, reading from
     * {@code source} and writing to {@code target}. Indices outside the
     * row are clamped, replicating the edge pixels.
     *
     * @param source the ARGB pixels to blur, row-major
     * @param target the array receiving the blurred pixels, row-major
     * @param width the image width in pixels
     * @param height the image height in pixels
     * @param radius the box radius in pixels
     */
    private static void boxBlurHorizontal(final int[] source, final int[] target,
                                          final int width, final int height,
                                          final int radius) {
        final int kernelSize = 2 * radius + 1;
        for (int y = 0; y < height; y++) {
            final int rowOffset = y * width;
            int alpha = 0;
            int red = 0;
            int green = 0;
            int blue = 0;
            for (int i = -radius; i <= radius; i++) {
                final int pixel = source[rowOffset + clampIndex(i, width)];
                alpha += pixel >>> 24;
                red += (pixel >> 16) & 0xFF;
                green += (pixel >> 8) & 0xFF;
                blue += pixel & 0xFF;
            }
            for (int x = 0; x < width; x++) {
                target[rowOffset + x] = (alpha / kernelSize) << 24
                        | (red / kernelSize) << 16
                        | (green / kernelSize) << 8
                        | blue / kernelSize;
                final int leaving = source[rowOffset + clampIndex(x - radius, width)];
                final int entering = source[rowOffset + clampIndex(x + radius + 1, width)];
                alpha += (entering >>> 24) - (leaving >>> 24);
                red += ((entering >> 16) & 0xFF) - ((leaving >> 16) & 0xFF);
                green += ((entering >> 8) & 0xFF) - ((leaving >> 8) & 0xFF);
                blue += (entering & 0xFF) - (leaving & 0xFF);
            }
        }
    }

    /**
     * One vertical box blur pass with a sliding window, reading from
     * {@code source} and writing to {@code target}. Indices outside the
     * column are clamped, replicating the edge pixels.
     *
     * @param source the ARGB pixels to blur, row-major
     * @param target the array receiving the blurred pixels, row-major
     * @param width the image width in pixels
     * @param height the image height in pixels
     * @param radius the box radius in pixels
     */
    private static void boxBlurVertical(final int[] source, final int[] target,
                                        final int width, final int height,
                                        final int radius) {
        final int kernelSize = 2 * radius + 1;
        for (int x = 0; x < width; x++) {
            int alpha = 0;
            int red = 0;
            int green = 0;
            int blue = 0;
            for (int i = -radius; i <= radius; i++) {
                final int pixel = source[clampIndex(i, height) * width + x];
                alpha += pixel >>> 24;
                red += (pixel >> 16) & 0xFF;
                green += (pixel >> 8) & 0xFF;
                blue += pixel & 0xFF;
            }
            for (int y = 0; y < height; y++) {
                target[y * width + x] = (alpha / kernelSize) << 24
                        | (red / kernelSize) << 16
                        | (green / kernelSize) << 8
                        | blue / kernelSize;
                final int leaving = source[clampIndex(y - radius, height) * width + x];
                final int entering = source[clampIndex(y + radius + 1, height) * width + x];
                alpha += (entering >>> 24) - (leaving >>> 24);
                red += ((entering >> 16) & 0xFF) - ((leaving >> 16) & 0xFF);
                green += ((entering >> 8) & 0xFF) - ((leaving >> 8) & 0xFF);
                blue += (entering & 0xFF) - (leaving & 0xFF);
            }
        }
    }

    /**
     * Clamps an index to the valid range of an axis.
     *
     * @param index the index to clamp
     * @param size the length of the axis
     * @return the clamped index
     */
    private static int clampIndex(final int index, final int size) {
        return Math.max(0, Math.min(index, size - 1));
    }
}
