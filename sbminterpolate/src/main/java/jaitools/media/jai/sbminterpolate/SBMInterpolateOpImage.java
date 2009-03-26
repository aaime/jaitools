/*
 * Copyright 2009 Michael Bedward
 * 
 * This file is part of jai-tools.

 * jai-tools is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the 
 * License, or (at your option) any later version.

 * jai-tools is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.

 * You should have received a copy of the GNU Lesser General Public 
 * License along with jai-tools.  If not, see <http://www.gnu.org/licenses/>.
 * 
 */

package jaitools.media.jai.sbminterpolate;

import java.awt.Rectangle;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.WritableRaster;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import javax.media.jai.AreaOpImage;
import javax.media.jai.BorderExtender;
import javax.media.jai.ImageLayout;
import javax.media.jai.KernelJAI;
import javax.media.jai.ROI;
import javax.media.jai.RasterAccessor;
import javax.media.jai.RasterFormatTag;

/**
 * THIS IS NOT READY FOR USE YET !!!
 *
 * An operator to fill areas of missing data in a source image using
 * the fractal interpolation method described in:
 * <blockquote>
 * J. C. Sprott, J. Bolliger, and D. J. Mladenoff (2002)<br>
 * Self-organized Criticality in Forest-landscape Evolution<br>
 * Phys. Lett. A 297, 267-271
 * </blockquote>
 * and
 * <blockquote>
 * J. C. Sprott (2004)<br>
 * A Method For Approximating Missing Data in Spatial Patterns<br>
 * Comput. & Graphics 28, 113-117
 * </blockquote>
 *
 * @see SBMInterpolateDescriptor Description of the algorithm and example
 * 
 * @author Michael Bedward
 */
final class SBMInterpolateOpImage extends AreaOpImage {

    /* Source image variables */
    private int[] srcBandOffsets;
    private int srcPixelStride;
    private int srcScanlineStride;

    /* Destination image variables */
    private int destWidth;
    private int destHeight;
    private int destBands;
    private int[] dstBandOffsets;
    private int dstPixelStride;
    private int dstScanlineStride;
    
    /* Kernel variables. */
    private boolean[] inKernel;
    private int kernelN;
    private int kernelW,  kernelH;
    private int kernelKeyX;
    private int kernelKeyY;
    
    private ROI roi;

    private Random rand;
    private int avNumSamples;


    /**
     * Constructor
     * @param source a RenderedImage.
     * @param extender a BorderExtender, or null.
     * @param config configurable attributes of the image (see {@link AreaOpImage})
     * @param layout an ImageLayout optionally containing the tile grid layout,
     *        SampleModel, and ColorModel, or null.
     * @param roi the ROI used to control masking; must contain the source image bounds
     * @param kernel the convolution kernel
     *
     * @throws IllegalArgumentException if the roi's bounds do not intersect with
     * those of the source image
     *
     * @see SBMInterpolateDescriptor
     */
    public SBMInterpolateOpImage(RenderedImage source,
            BorderExtender extender,
            Map config,
            ImageLayout layout,
            ROI roi,
            KernelJAI kernel,
            Integer avNumSamples) {
        
        super(source,
                layout,
                config,
                true,
                extender,
                kernel.getLeftPadding(),
                kernel.getRightPadding(),
                kernel.getTopPadding(),
                kernel.getBottomPadding());

        Rectangle sourceBounds = new Rectangle(
                source.getMinX(), source.getMinY(), source.getWidth(), source.getHeight());
        
        if (!roi.getBounds().intersects(sourceBounds)) {
            throw new IllegalArgumentException("The bounds of the ROI must intersect with the source image");
        }

        this.roi = roi;

        /*
         * Kernel dimensions and position of the key element in the
         * boolean array
         */
        kernelW = kernel.getWidth();
        kernelH = kernel.getHeight();
        kernelKeyX = kernel.getXOrigin();
        kernelKeyY = kernel.getYOrigin();
        int kernelKey = kernelKeyY * kernelW + kernelKeyX;

        /*
         * Convert the kernel to a boolean array by treating all
         * zero values as false and non-zero values as true
         */
        float eps = 1.0e-8f;
        inKernel = new boolean[kernel.getKernelData().length];
        kernelN = 0;
        int k = 0;
        for (Float f : kernel.getKernelData()) {
            if (k != kernelKey && Math.abs(f) > eps) {
                inKernel[k++] = true;
                kernelN++ ;
            } else {
                inKernel[k++] = false;
            }
        }

        /* sampling variables */
        this.avNumSamples = avNumSamples;
        rand = new Random();

    }

    /**
     * Performs convolution on a specified rectangle. 
     *
     * @param sources an array of source Rasters, guaranteed to provide all
     *        necessary source data for computing the output.
     * @param dest a WritableRaster tile containing the area to be computed.
     * @param destRect the rectangle within dest to be processed.
     */
    @Override
    protected void computeRect(Raster[] sources,
            WritableRaster dest,
            Rectangle destRect) {

        RasterFormatTag[] formatTags = getFormatTags();

        Raster source = sources[0];
        Rectangle srcRect = mapDestRect(destRect, 0);


        RasterAccessor srcAcc =
                new RasterAccessor(source, srcRect,
                formatTags[0], getSourceImage(0).getColorModel());
        
        RasterAccessor destAcc =
                new RasterAccessor(dest, destRect,
                formatTags[1], getColorModel());

        interpolate(srcAcc, destAcc, roi);
    }

    /**
     * Initialize common variables then delegate the interpolation to
     * one of the data-type-specific methods
     * 
     * @param srcAcc source raster accessor
     * @param destAcc dest raster accessor
     * @param roi the ROI that defines areas of missing data
     */
    private void interpolate(RasterAccessor srcAcc, RasterAccessor destAcc, ROI roi) {

        destWidth = destAcc.getWidth();
        destHeight = destAcc.getHeight();
        destBands = destAcc.getNumBands();

        dstBandOffsets = destAcc.getBandOffsets();
        dstPixelStride = destAcc.getPixelStride();
        dstScanlineStride = destAcc.getScanlineStride();

        srcBandOffsets = srcAcc.getBandOffsets();
        srcPixelStride = srcAcc.getPixelStride();
        srcScanlineStride = srcAcc.getScanlineStride();

        switch (destAcc.getDataType()) {
            case DataBuffer.TYPE_BYTE:
                interpolateAsByteData(srcAcc, destAcc, roi);
                break;

            /*
            case DataBuffer.TYPE_INT:
                interpolateAsIntData(srcAcc, destAcc, roi);
                break;

            case DataBuffer.TYPE_SHORT:
                interpolateAsShortData(srcAcc, destAcc, roi);
                break;
            case DataBuffer.TYPE_USHORT:
                interpolateAsUShortData(srcAcc, destAcc, roi);
                break;
            case DataBuffer.TYPE_FLOAT:
                interpolateAsFloatData(srcAcc, destAcc, roi);
                break;
            case DataBuffer.TYPE_DOUBLE:
                interpolateAsDoubleData(srcAcc, destAcc, roi);
                break;
             */

            default:
                throw new IllegalStateException("destination is not TYPE_BYTE");
        }

        if (destAcc.isDataCopy()) {
            destAcc.clampDataArrays();
            destAcc.copyDataToRaster();
        }
    }

    private void interpolateAsByteData(RasterAccessor srcAcc, RasterAccessor destAcc, ROI roi) {

        byte[][] srcData = srcAcc.getByteDataArrays();
        byte[][] destData = destAcc.getByteDataArrays();

        List<Integer> missingDataOffsets = new ArrayList<Integer>();

        byte[] sampleData = new byte[kernelN];
        int numSamples;
        int val;

        /*
         * We begin by generating some initial data for the missing data
         * regions. Since we are scanning by row then col, we will be getting
         * an edge pixel first on each row so there will be some non-missing
         * data to sample
         */

        boolean firstBand = true;
        for (int band = 0; band < destBands; band++) {
            int destY = destAcc.getY();
            byte destBandData[] = destData[band];
            byte srcBandData[] = srcData[band];
            int srcScanlineOffset = srcBandOffsets[band];
            int dstScanlineOffset = dstBandOffsets[band];

            boolean[] destFilled = new boolean[destBandData.length];
            Arrays.fill(destFilled, false);
            int destFilledIndex = 0;

            for (int j = 0; j < destHeight; j++, destY++) {
                int destX = destAcc.getX();
                int srcPixelOffset = srcScanlineOffset;
                int dstPixelOffset = dstScanlineOffset;

                for (int i = 0; i < destWidth; i++, destX++, destFilledIndex++) {
                    if (roi.contains(destX, destY)) {
                        if (firstBand) {
                            missingDataOffsets.add(dstPixelOffset);
                        }

                        int imageVerticalOffset = srcPixelOffset;
                        numSamples = 0;
                        int srcY = destY - kernelKeyY;

                        for (int u = 0, k = 0; u < kernelH; u++, srcY++) {
                            int imageOffset = imageVerticalOffset;
                            int srcX = destX - kernelKeyX;

                            for (int v = 0; v < kernelW; v++, k++, srcX++) {
                                if (inKernel[k]) {
                                    if (!roi.contains(srcX, srcY)) {
                                        sampleData[numSamples++] = (byte) (srcBandData[imageOffset] & 0xff);
                                    } else if (/* destination filled*/) {
                                        sampleData[numSamples++] = destBandData[???];
                                    }
                                }
                                imageOffset += srcPixelStride;
                            }
                            imageVerticalOffset += srcScanlineStride;
                        }

                        val = sampleData[rand.nextInt(numSamples)] & 0xff;
                        destFilled[destFilledIndex] = true;

                    } else { // this pixel is not in a missing data region
                        val = srcBandData[srcPixelOffset] & 0xff;
                    }

                    if (val < 0) {
                        val = 0;
                    } else if (val > 255) {
                        val = 255;
                    }

                    destBandData[dstPixelOffset] = (byte) val;
                    srcPixelOffset += srcPixelStride;
                    dstPixelOffset += dstPixelStride;
                }

                srcScanlineOffset += srcScanlineStride;
                dstScanlineOffset += dstScanlineStride;
            }

            firstBand = false;
        }

        /*
         * Now we do the voter model sampling within the missing data areas
         */
        int N = avNumSamples * missingDataOffsets.size();
        for (int band = 0; band < destBands; band++) {
            byte destBandData[] = destData[band];

            for (int n = 0; n < N; n++) {
                int index = rand.nextInt(missingDataOffsets.size());
                int destOffset = missingDataOffsets.get(index);
                numSamples = 0;

                int destSampleOffset = destOffset - (kernelKeyY * dstScanlineStride) - (kernelKeyX * dstPixelStride);
                int kernelLineStride = kernelW * dstPixelStride;

                for (int u = 0, k = 0; u < kernelH; u++) {
                    for (int v = 0; v < kernelW; v++, k++) {
                        if (inKernel[k]) {
                            sampleData[numSamples++] = destBandData[destSampleOffset];
                        }
                        destSampleOffset += dstPixelStride;
                    }
                    destSampleOffset += (dstScanlineStride - kernelLineStride);
                }

                val = sampleData[rand.nextInt(numSamples)];
                destBandData[destOffset] = (byte) val;
            }
        }
    }


}
