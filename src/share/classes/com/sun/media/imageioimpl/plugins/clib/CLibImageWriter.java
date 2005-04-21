/*
 * $RCSfile: CLibImageWriter.java,v $
 *
 * 
 * Copyright (c) 2005 Sun Microsystems, Inc. All  Rights Reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met: 
 * 
 * - Redistribution of source code must retain the above copyright 
 *   notice, this  list of conditions and the following disclaimer.
 * 
 * - Redistribution in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in 
 *   the documentation and/or other materials provided with the
 *   distribution.
 * 
 * Neither the name of Sun Microsystems, Inc. or the names of 
 * contributors may be used to endorse or promote products derived 
 * from this software without specific prior written permission.
 * 
 * This software is provided "AS IS," without a warranty of any 
 * kind. ALL EXPRESS OR IMPLIED CONDITIONS, REPRESENTATIONS AND 
 * WARRANTIES, INCLUDING ANY IMPLIED WARRANTY OF MERCHANTABILITY, 
 * FITNESS FOR A PARTICULAR PURPOSE OR NON-INFRINGEMENT, ARE HEREBY
 * EXCLUDED. SUN MIDROSYSTEMS, INC. ("SUN") AND ITS LICENSORS SHALL 
 * NOT BE LIABLE FOR ANY DAMAGES SUFFERED BY LICENSEE AS A RESULT OF 
 * USING, MODIFYING OR DISTRIBUTING THIS SOFTWARE OR ITS
 * DERIVATIVES. IN NO EVENT WILL SUN OR ITS LICENSORS BE LIABLE FOR 
 * ANY LOST REVENUE, PROFIT OR DATA, OR FOR DIRECT, INDIRECT, SPECIAL,
 * CONSEQUENTIAL, INCIDENTAL OR PUNITIVE DAMAGES, HOWEVER CAUSED AND
 * REGARDLESS OF THE THEORY OF LIABILITY, ARISING OUT OF THE USE OF OR
 * INABILITY TO USE THIS SOFTWARE, EVEN IF SUN HAS BEEN ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGES. 
 * 
 * You acknowledge that this software is not designed or intended for 
 * use in the design, construction, operation or maintenance of any 
 * nuclear facility. 
 *
 * $Revision: 1.1 $
 * $Date: 2005-02-11 05:01:27 $
 * $State: Exp $
 */
package com.sun.media.imageioimpl.plugins.clib;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.color.ColorSpace;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferUShort;
import java.awt.image.IndexColorModel;
import java.awt.image.MultiPixelPackedSampleModel;
import java.awt.image.PixelInterleavedSampleModel;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.SampleModel;
import java.awt.image.SinglePixelPackedSampleModel;
import java.awt.image.WritableRaster;
import java.io.IOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageWriter;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageWriterSpi;
import com.sun.medialib.codec.jiio.mediaLibImage;

public abstract class CLibImageWriter extends ImageWriter {
    /**
     * Returns the data array from the <code>DataBuffer</code>.
     */
    private static final Object getDataBufferData(DataBuffer db) {
        Object data;

        int dType = db.getDataType();
        switch (dType) {
        case DataBuffer.TYPE_BYTE:
            data = ((DataBufferByte)db).getData();
            break;
        case DataBuffer.TYPE_USHORT:
            data = ((DataBufferUShort)db).getData();
            break;
        default:
            throw new IllegalArgumentException
                (I18N.getString("Generic0")+" "+dType);
        }

        return data;
    }

    /**
     * Returns the mediaLib type enum given the Java2D type enum.
     */
    private static final int getMediaLibDataType(int dataType) {
        int mlibType;

        switch (dataType) {
        case DataBuffer.TYPE_BYTE:
            mlibType = mediaLibImage.MLIB_BYTE;
            break;
        case DataBuffer.TYPE_USHORT:
            mlibType = mediaLibImage.MLIB_USHORT;
            break;
        default:
            throw new IllegalArgumentException
                (I18N.getString("Generic0")+" "+dataType);
        }

        return mlibType;
    }

    /**
     * Subsamples and sub-bands the input <code>Raster</code> over a
     * sub-region and stores the result in a <code>WritableRaster</code>.
     *
     * @param src The source <code>Raster</code>
     * @param sourceBands The source bands to use; may be <code>null</code>
     * @param subsampleX The subsampling factor along the horizontal axis.
     * @param subsampleY The subsampling factor along the vertical axis.
     * in which case all bands will be used.
     * @param dst The destination <code>WritableRaster</code>.
     * @throws IllegalArgumentException if <code>source</code> is
     * <code>null</code> or empty, <code>dst</code> is <code>null</code>,
     * <code>sourceBands.length</code> exceeds the number of bands in
     * <code>source</code>, or <code>sourcBands</code> contains an element
     * which is negative or greater than or equal to the number of bands
     * in <code>source</code>.
     */
    private static void reformat(Raster source,
                                 int[] sourceBands,
                                 int subsampleX,
                                 int subsampleY,
                                 WritableRaster dst) {
        // Check for nulls.
        if(source == null) {
            throw new IllegalArgumentException("source == null!");
        } else if(dst == null) {
            throw new IllegalArgumentException("dst == null!");
        }

        // Validate the source bounds. XXX is this needed?
        Rectangle sourceBounds = source.getBounds();
        if(sourceBounds.isEmpty()) {
            throw new IllegalArgumentException
                ("source.getBounds().isEmpty()!");
        }

        // Check sub-banding.
        boolean isSubBanding = false;
        int numSourceBands = source.getSampleModel().getNumBands();
        if(sourceBands != null) {
            if(sourceBands.length > numSourceBands) {
                throw new IllegalArgumentException
                    ("sourceBands.length > numSourceBands!");
            }

            boolean isRamp = sourceBands.length == numSourceBands;
            for(int i = 0; i < sourceBands.length; i++) {
                if(sourceBands[i] < 0 || sourceBands[i] >= numSourceBands) {
                    throw new IllegalArgumentException
                        ("sourceBands[i] < 0 || sourceBands[i] >= numSourceBands!");
                } else if(sourceBands[i] != i) {
                    isRamp = false;
                }
            }

            isSubBanding = !isRamp;
        }

        // Allocate buffer for a single source row.
        int sourceWidth = sourceBounds.width;
        int[] pixels = new int[sourceWidth*numSourceBands];

        // Initialize variables used in loop.
        int sourceX = sourceBounds.x;
        int sourceY = sourceBounds.y;
        int numBands = sourceBands != null ?
            sourceBands.length : numSourceBands;
        int dstWidth = dst.getWidth();
        int dstYMax = dst.getHeight() - 1;
        int copyFromIncrement = numSourceBands*subsampleX;

        // Loop over source rows, subsample each, and store in destination.
        for(int dstY = 0; dstY <= dstYMax; dstY++) {
            // Read one row.
            source.getPixels(sourceX, sourceY, sourceWidth, 1, pixels);

            // Copy within the same buffer by left shifting.
            if(isSubBanding) {
                int copyFrom = 0;
                int copyTo = 0;
                for(int i = 0; i < dstWidth; i++) {
                    for(int j = 0; j < numBands; j++) {
                        pixels[copyTo++] = pixels[copyFrom + sourceBands[j]];
                    }
                    copyFrom += copyFromIncrement;
                }
            } else {
                int copyFrom = copyFromIncrement;
                int copyTo = numSourceBands;
                // Start from index 1 as no need to copy the first pixel.
                for(int i = 1; i < dstWidth; i++) {
                    int k = copyFrom;
                    for(int j = 0; j < numSourceBands; j++) {
                        pixels[copyTo++] = pixels[k++];
                    }
                    copyFrom += copyFromIncrement;
                }
            }

            // Set the destionation row.
            dst.setPixels(0, dstY, dstWidth, 1, pixels);

            // Increment the source row.
            sourceY += subsampleY;
        }
    }

    protected CLibImageWriter(ImageWriterSpi originatingProvider) {
        super(originatingProvider);
    }

    public IIOMetadata convertImageMetadata(IIOMetadata inData,
                                            ImageTypeSpecifier imageType,
                                            ImageWriteParam param) {
        return null;
    }

    public IIOMetadata convertStreamMetadata(IIOMetadata inData,
                                             ImageWriteParam param) {
        return null;
    }

    public IIOMetadata
        getDefaultImageMetadata(ImageTypeSpecifier imageType,
                                ImageWriteParam param) {
        return null;
    }

    public IIOMetadata getDefaultStreamMetadata(ImageWriteParam param) {
        return null;
    }

    /* XXX
    protected int getSignificantBits(RenderedImage image) {
        SampleModel sampleModel = image.getSampleModel();
        int numBands = sampleModel.getNumBands();
        int[] sampleSize = sampleModel.getSampleSize();
        int significantBits = sampleSize[0];
        for(int i = 1; i < numBands; i++) {
            significantBits = Math.max(significantBits, sampleSize[i]);
        }

        return significantBits;
    }
    */

    // Code copied from ImageReader.java with ImageReadParam replaced
    // by ImageWriteParam.
    private static final Rectangle getSourceRegion(ImageWriteParam param,
                                                   int sourceMinX,
                                                   int sourceMinY,
                                                   int srcWidth,
                                                   int srcHeight) {
        Rectangle sourceRegion =
            new Rectangle(sourceMinX, sourceMinY, srcWidth, srcHeight);
        if (param != null) {
            Rectangle region = param.getSourceRegion();
            if (region != null) {
                sourceRegion = sourceRegion.intersection(region);
            }

            int subsampleXOffset = param.getSubsamplingXOffset();
            int subsampleYOffset = param.getSubsamplingYOffset();
            sourceRegion.x += subsampleXOffset;
            sourceRegion.y += subsampleYOffset;
            sourceRegion.width -= subsampleXOffset;
            sourceRegion.height -= subsampleYOffset;
        }

        return sourceRegion;
    }

    protected mediaLibImage getMediaLibImage(RenderedImage image,
                                             ImageWriteParam param,
                                             boolean allowBilevel) {
        // Determine the source region.
        Rectangle sourceRegion = getSourceRegion(param,
                                                 image.getMinX(),
                                                 image.getMinY(),
                                                 image.getWidth(),
                                                 image.getHeight());

        if(sourceRegion.isEmpty()) {
            throw new IllegalArgumentException("sourceRegion.isEmpty()");
        }

        // Check whether reformatting is necessary to conform to mediaLib
        // image format (packed bilevel if allowed or ((G|I)|(RGB))[A]).

        // Flag indicating need to reformat data.
        boolean reformatData = false;

        // Flag indicating bilevel data.
        boolean isBilevel = false;

        // Get the SampleModel.
        SampleModel sampleModel = image.getSampleModel();

        // Get the number of bands.
        int numSourceBands = sampleModel.getNumBands();

        // Get the source sub-banding array.
        int[] sourceBands = param != null ? param.getSourceBands() : null;

        // Check for non-nominal sub-banding.
        int numBands;
        if(sourceBands != null) {
            numBands = sourceBands.length;
            if(numBands != numSourceBands) {
                // The number of bands must be the same.
                reformatData = true;
            } else {
                // The band order must not change.
                for(int i = 0; i < numSourceBands; i++) {
                    if(sourceBands[i] != i) {
                        reformatData = true;
                        break;
                    }
                }
            }
        } else {
            numBands = numSourceBands;
        }

        // If sub-banding does not dictate reformatting, check subsampling..
        if(!reformatData && param != null &&
           (param.getSourceXSubsampling() != 1 ||
            param.getSourceXSubsampling() != 1)) {
            reformatData = true;
        }

        // If sub-banding does not dictate reformatting check SampleModel.
        if(!reformatData) {
            if(sampleModel instanceof ComponentSampleModel) {
                ComponentSampleModel csm = (ComponentSampleModel)sampleModel;

                if(csm.getPixelStride() != numSourceBands) {
                    // Need each row to be contiguous samples.
                    reformatData = true;
                } else {
                    // Need band offsets to increase from zero.
                    int[] bandOffsets = csm.getBandOffsets();
                    for(int i = 0; i < numSourceBands; i++) {
                        if(bandOffsets[i] != i) {
                            reformatData = true;
                            break;
                        }
                    }
                }
            } else if(allowBilevel &&
                      sampleModel.getNumBands() == 1 &&
                      sampleModel.getSampleSize(0) == 1 &&
                      sampleModel instanceof MultiPixelPackedSampleModel &&
                      sampleModel.getDataType() == DataBuffer.TYPE_BYTE) {
                // Need continguous packed bits.
                MultiPixelPackedSampleModel mppsm =
                    (MultiPixelPackedSampleModel)sampleModel;
                if(mppsm.getPixelBitStride() == 1) {
                    isBilevel = true;
                } else {
                    reformatData = true;
                }
            } else {
                // All other cases.
                reformatData = true;
            }
        }

        // Variable for the eventual destination data.
        Raster raster = null;

        if(reformatData) {
            // Determine the maximum bit depth.
            int[] sampleSize = sampleModel.getSampleSize();
            int bitDepthMax = sampleSize[0];
            for(int i = 1; i < numSourceBands; i++) {
                bitDepthMax = Math.max(bitDepthMax, sampleSize[i]);
            }

            // Set the data type as a function of bit depth.
            int dataType;
            if(bitDepthMax <= 8) {
                dataType = DataBuffer.TYPE_BYTE;
            } else if(bitDepthMax <= 16) {
                dataType = DataBuffer.TYPE_USHORT;
            } else {
                throw new UnsupportedOperationException
                    (I18N.getString("CLibImageWriter0")+" "+bitDepthMax);
            }

            // Determine the width and height.
            int width;
            int height;
            if(param != null) {
                int subsampleX = param.getSourceXSubsampling();
                int subsampleY = param.getSourceYSubsampling();
                width = (sourceRegion.width + subsampleX - 1)/subsampleX;
                height = (sourceRegion.height + subsampleY - 1)/subsampleY;
            } else {
                width = sourceRegion.width;
                height = sourceRegion.height;
            }

            // Load a ramp for band offsets.
            int[] newBandOffsets = new int[numBands];
            for(int i = 0; i < numBands; i++) {
                newBandOffsets[i] = i;
            }

            // Create a new SampleModel.
            SampleModel newSampleModel;
            if(allowBilevel &&
               sampleModel.getNumBands() == 1 &&
               bitDepthMax == 1) {
                // Bilevel image.
                newSampleModel =
                    new MultiPixelPackedSampleModel(dataType,
                                                    width,
                                                    height,
                                                    1);
                isBilevel = true;
            } else {
                // Pixel interleaved image.
                newSampleModel =
                    new PixelInterleavedSampleModel(dataType,
                                                    width,
                                                    height,
                                                    newBandOffsets.length,
                                                    width*numSourceBands,
                                                    newBandOffsets);
            }

            // Create a new Raster at (0,0).
            WritableRaster newRaster =
                Raster.createWritableRaster(newSampleModel, null);

            // Populate the new Raster.
            if(param != null &&
               (param.getSourceXSubsampling() != 1 ||
                param.getSourceXSubsampling() != 1)) {
                // Subsampling, possibly with sub-banding.
                reformat(image.getData(sourceRegion),
                         sourceBands,
                         param.getSourceXSubsampling(),
                         param.getSourceYSubsampling(),
                         newRaster);
            } else if(sourceBands == null &&
                      image.getSampleModel().getClass().isInstance
                      (newSampleModel) &&
                      newSampleModel.getTransferType() ==
                      image.getSampleModel().getTransferType()) {
                // Neither subsampling nor sub-banding.
                WritableRaster translatedChild =
                    newRaster.createWritableTranslatedChild(sourceRegion.x,
                                                            sourceRegion.y);
                // Use copyData() to avoid potentially cobbling the entire
                // source region into an extra Raster via getData().
                image.copyData(translatedChild);
            } else {
                // Cannot use copyData() so use getData() to retrieve and
                // possibly sub-band the source data and use setRect().
                WritableRaster translatedChild =
                    newRaster.createWritableTranslatedChild(sourceRegion.x,
                                                            sourceRegion.y);
                Raster sourceRaster = image.getData(sourceRegion);
                if(sourceBands != null) {
                    // Copy only the requested bands.
                    sourceRaster =
                        sourceRaster.createChild(sourceRegion.x,
                                                 sourceRegion.y,
                                                 sourceRegion.width,
                                                 sourceRegion.height,
                                                 sourceRegion.x,
                                                 sourceRegion.y,
                                                 sourceBands);
                }

                // Get the region from the image and set it into the Raster.
                translatedChild.setRect(sourceRaster);
            }

            // Replace Raster and SampleModel.
            raster = newRaster;
            sampleModel = newRaster.getSampleModel();
        } else { // !reformatData
            // No reformatting needed.
            raster = image.getData(sourceRegion).createTranslatedChild(0, 0);
            sampleModel = raster.getSampleModel();
        }

        // The mediaLib image.
        mediaLibImage mlibImage = null;

        // Create a mediaLibImage with reference to the Raster data.
        if(isBilevel) {
            // Bilevel image: either is was already bilevel or was
            // formatted to bilevel.

            MultiPixelPackedSampleModel mppsm =
                ((MultiPixelPackedSampleModel)sampleModel);

            // Get the line stride.
            int stride = mppsm.getScanlineStride();

            // Determine the offset to the start of the data.
            int offset =
                raster.getDataBuffer().getOffset() -
                raster.getSampleModelTranslateY()*stride -
                raster.getSampleModelTranslateX()/8 +
                mppsm.getOffset(0, 0);

            // Get a reference to the internal data array.
            Object bitData = getDataBufferData(raster.getDataBuffer());

            mlibImage = new mediaLibImage(mediaLibImage.MLIB_BIT,
                                          1,
                                          raster.getWidth(),
                                          raster.getHeight(),
                                          stride,
                                          offset,
                                          (byte)mppsm.getBitOffset(0),
                                          bitData);
        } else {
            // If the image is not bilevel then it has to be component.
            ComponentSampleModel csm = (ComponentSampleModel)sampleModel;

            // Set the mediaLib data type
            int mlibDataType = getMediaLibDataType(sampleModel.getDataType());

            // Get a reference to the internal data array.
            Object data = getDataBufferData(raster.getDataBuffer());

            // Get the line stride.
            int stride = csm.getScanlineStride();

            // Determine the offset to the start of the data. The
            // sampleModelTranslate parameters are the translations from
            // Raster to SampleModel coordinates and must be subtracted
            // from the Raster coordinates.
            int offset = csm.getOffset(raster.getMinX() -
                                       raster.getSampleModelTranslateX(),
                                       raster.getMinY() -
                                       raster.getSampleModelTranslateY());

            // Create the image.
            mlibImage =
                new mediaLibImage(mlibDataType,
                                  numSourceBands,
                                  raster.getWidth(),
                                  raster.getHeight(),
                                  stride,
                                  offset,
                                  data);
        }

        return mlibImage;
    }
}