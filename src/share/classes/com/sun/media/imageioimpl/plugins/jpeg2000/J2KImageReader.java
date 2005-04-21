/*
 * $RCSfile: J2KImageReader.java,v $
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
 * $Date: 2005-02-11 05:01:33 $
 * $State: Exp $
 */
package com.sun.media.imageioimpl.plugins.jpeg2000;

import java.awt.Rectangle;
import java.awt.Point;

import javax.imageio.IIOException;
import javax.imageio.ImageReader;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import com.sun.media.imageio.plugins.jpeg2000.J2KImageReadParam;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;

import java.io.*;
import java.util.List;
import java.util.Iterator;
import java.util.ArrayList;

import jj2000.j2k.quantization.dequantizer.*;
import jj2000.j2k.wavelet.synthesis.*;
import jj2000.j2k.image.invcomptransf.*;
import jj2000.j2k.fileformat.reader.*;
import jj2000.j2k.codestream.reader.*;
import jj2000.j2k.entropy.decoder.*;
import jj2000.j2k.codestream.*;
import jj2000.j2k.decoder.*;
import jj2000.j2k.image.*;
import jj2000.j2k.util.*;
import jj2000.j2k.roi.*;
import jj2000.j2k.io.*;
import jj2000.j2k.*;

/** This class is the Java Image IO plugin reader for JPEG 2000 JP2 image file
 *  format.  It has the capability to load the compressed bilevel images,
 *  color-indexed byte images, or multi-band images in byte/ushort/short/int
 *  data type.  It may subsample the image, select bands, clip the image,
 *  and shift the decoded image origin if the proper decoding parameter
 *  are set in the provided <code>J2KImageReadParam</code>.
 */
public class J2KImageReader extends ImageReader {
    /** The input stream where reads from */
    private ImageInputStream iis = null;

    /** Indicates whether the header is read. */
    private boolean gotHeader = false;

    /** The image width. */
    private int width;

    /** The image height. */
    private int height;

    /** Image metadata, valid for the imageMetadataIndex only. */
    private J2KMetadata imageMetadata = null;

    /** The image index for the cached metadata. */
    private int imageMetadataIndex = -1;

    /** The J2K HeaderDecoder defined in jj2000 packages.  Used to extract image
     *  header information.
     */
    private HeaderDecoder hd;

    /** The J2KReadState for this reading session based on the current input
     *  and J2KImageReadParam.
     */
    private J2KReadState readState = null;

    /** Wrapper for the protected method <code>computeRegions</code>.  So it
     *  can be access from the classes which are not in <code>ImageReader</code>
     *  hierachy.
     */
    public static void computeRegionsWrapper(ImageReadParam param,
                                             boolean allowZeroDestOffset,
                                             int srcWidth,
                                             int srcHeight,
                                             BufferedImage image,
                                             Rectangle srcRegion,
                                             Rectangle destRegion) {
        if (srcRegion == null) {
            throw new IllegalArgumentException(I18N.getString("J2KImageReader0"));
        }
        if (destRegion == null) {
            throw new IllegalArgumentException(I18N.getString("J2KImageReader1"));
        }

        // Clip that to the param region, if there is one
        int periodX = 1;
        int periodY = 1;
        int gridX = 0;
        int gridY = 0;
        if (param != null) {
            Rectangle paramSrcRegion = param.getSourceRegion();
            if (paramSrcRegion != null) {
                srcRegion.setBounds(srcRegion.intersection(paramSrcRegion));
            }
            periodX = param.getSourceXSubsampling();
            periodY = param.getSourceYSubsampling();
            gridX = param.getSubsamplingXOffset();
            gridY = param.getSubsamplingYOffset();
            srcRegion.translate(gridX, gridY);
            srcRegion.width -= gridX;
            srcRegion.height -= gridY;
            if(allowZeroDestOffset) {
                destRegion.setLocation(param.getDestinationOffset());
            } else {
                Point destOffset = param.getDestinationOffset();
                if(destOffset.x != 0 || destOffset.y != 0) {
                    destRegion.setLocation(param.getDestinationOffset());
                }
            }
        }

        // Now clip any negative destination offsets, i.e. clip
        // to the top and left of the destination image
        if (destRegion.x < 0) {
            int delta = -destRegion.x*periodX;
            srcRegion.x += delta;
            srcRegion.width -= delta;
            destRegion.x = 0;
        }
        if (destRegion.y < 0) {
            int delta = -destRegion.y*periodY;
            srcRegion.y += delta;
            srcRegion.height -= delta;
            destRegion.y = 0;
        }

        // Now clip the destination Region to the subsampled width and height
        int subsampledWidth = (srcRegion.width + periodX - 1)/periodX;
        int subsampledHeight = (srcRegion.height + periodY - 1)/periodY;
        destRegion.width = subsampledWidth;
        destRegion.height = subsampledHeight;

        // Now clip that to right and bottom of the destination image,
        // if there is one, taking subsampling into account
        if (image != null) {
            Rectangle destImageRect = new Rectangle(0, 0,
                                                    image.getWidth(),
                                                    image.getHeight());
            destRegion.setBounds(destRegion.intersection(destImageRect));
            if (destRegion.isEmpty()) {
                throw new IllegalArgumentException
                    (I18N.getString("J2KImageReader2"));
            }

            int deltaX = destRegion.x + subsampledWidth - image.getWidth();
            if (deltaX > 0) {
                srcRegion.width -= deltaX*periodX;
            }
            int deltaY =  destRegion.y + subsampledHeight - image.getHeight();
            if (deltaY > 0) {
                srcRegion.height -= deltaY*periodY;
            }
        }
        if (srcRegion.isEmpty() || destRegion.isEmpty()) {
            throw new IllegalArgumentException(I18N.getString("J2KImageReader3"));
        }
    }

    /** Wrapper for the protected method <code>checkReadParamBandSettings</code>.
     *  So it can be access from the classes which are not in
     *  <code>ImageReader</code> hierachy.
     */
    public static void checkReadParamBandSettingsWrapper(ImageReadParam param,
                                                  int numSrcBands,
                                                  int numDstBands) {
        checkReadParamBandSettings(param, numSrcBands, numDstBands);
    }

    /** Wrapper for the protected method <code>processImageUpdate</code>
     *  So it can be access from the classes which are not in
     *  <code>ImageReader</code> hierachy.
     */
    public void processImageUpdateWrapper(BufferedImage theImage,
                                      int minX, int minY,
                                      int width, int height,
                                      int periodX, int periodY,
                                      int[] bands) {
        processImageUpdate(theImage,
                                  minX, minY,
                                  width, height,
                                  periodX, periodY,
                                  bands);
    }

    /** Wrapper for the protected method <code>processImageProgress</code>
     *  So it can be access from the classes which are not in
     *  <code>ImageReader</code> hierachy.
     */
    public void processImageProgressWrapper(float percentageDone) {
        processImageProgress(percentageDone);
    }

    /** Constructs <code>J2KImageReader</code> from the provided
     *  <code>ImageReaderSpi</code>.
     */
    public J2KImageReader(ImageReaderSpi originator) {
        super(originator);
    }

    /** Overrides the method defined in the superclass. */
    public void setInput(Object input,
                         boolean seekForwardOnly,
                         boolean ignoreMetadata) {
        super.setInput(input, seekForwardOnly, ignoreMetadata);
        this.ignoreMetadata = ignoreMetadata;
        iis = (ImageInputStream) input; // Always works
        imageMetadata = null;
    }

    /** Overrides the method defined in the superclass. */
    public int getNumImages(boolean allowSearch) throws IOException {
        return 1;
    }

    public int getWidth(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        readHeader();
        return width;
    }

    public int getHeight(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        readHeader();
        return height;
    }

    public int getTileGridXOffset(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        readHeader();
        return hd.getTilingOrigin(null).x;
    }

    public int getTileGridYOffset(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        readHeader();
        return hd.getTilingOrigin(null).y;
    }

    public int getTileWidth(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        readHeader();
        return hd.getNomTileWidth();
    }

    public int getTileHeight(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        readHeader();
        return hd.getNomTileHeight();
    }

    private void checkIndex(int imageIndex) {
        if (imageIndex != 0) {
            throw new IndexOutOfBoundsException(I18N.getString("J2KImageReader4"));
        }
    }

    public void readHeader() {
        if (gotHeader)
            return;

        if (readState == null)
            readState =
                new J2KReadState(iis,
                                 new J2KImageReadParamJava(getDefaultReadParam()),
                                 this);

        hd = readState.getHeader();
        gotHeader = true;

        this.width = hd.getImgWidth();
        this.height = hd.getImgHeight();
    }

    public Iterator getImageTypes(int imageIndex)
        throws IOException {
        checkIndex(imageIndex);
        readHeader();
        if (readState != null) {
            ArrayList list = new ArrayList();
            list.add(new ImageTypeSpecifier(readState.getColorModel(),
                                            readState.getSampleModel()));
            return list.iterator();
        }
        return null;
    }

    public ImageReadParam getDefaultReadParam() {
        return new J2KImageReadParam();
    }

    public IIOMetadata getImageMetadata(int imageIndex)
        throws IOException {
        checkIndex(imageIndex);
        if (ignoreMetadata)
            return null;

        if (imageMetadata == null) {
            iis.mark();
            imageMetadata = new J2KMetadata(iis, this);
            iis.reset();
        }
        return imageMetadata;
    }

    public IIOMetadata getStreamMetadata() throws IOException {
        return null;
    }

    public BufferedImage read(int imageIndex, ImageReadParam param)
        throws IOException {
        checkIndex(imageIndex);
        clearAbortRequest();
        processImageStarted(imageIndex);

        if (param == null)
            param = getDefaultReadParam();

        param = new J2KImageReadParamJava(param);

        if (!ignoreMetadata) {
            imageMetadata = new J2KMetadata();
            readState = new J2KReadState(iis,
                                         (J2KImageReadParamJava)param,
                                         imageMetadata,
                                         this);
        } else
            readState = new J2KReadState(iis,
                                         (J2KImageReadParamJava)param,
                                         this);

        BufferedImage bi = readState.readBufferedImage();
        if (abortRequested())
            processReadAborted();
        else
            processImageComplete();
        return bi;
    }

    public RenderedImage readAsRenderedImage(int imageIndex,
                                             ImageReadParam param)
                                             throws IOException {
        checkIndex(imageIndex);
        RenderedImage ri = null;
        clearAbortRequest();
        processImageStarted(imageIndex);

        if (param == null)
            param = getDefaultReadParam();

        param = new J2KImageReadParamJava(param);
        if (!ignoreMetadata) {
            if (imageMetadata == null)
                imageMetadata = new J2KMetadata();
            ri = new J2KRenderedImage(iis,
                                        (J2KImageReadParamJava)param,
                                        imageMetadata,
                                        this);
        }
        else
            ri = new J2KRenderedImage(iis, (J2KImageReadParamJava)param, this);
        if (abortRequested())
            processReadAborted();
        else
            processImageComplete();
        return ri;
    }

    public boolean canReadRaster() {
        return true;
    }

    public boolean isRandomAccessEasy(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        return false;
    }

    public Raster readRaster(int imageIndex,
                             ImageReadParam param) throws IOException {
        checkIndex(imageIndex);
        processImageStarted(imageIndex);
        param = new J2KImageReadParamJava(param);

        if (!ignoreMetadata) {
            imageMetadata = new J2KMetadata();
            readState = new J2KReadState(iis,
                                         (J2KImageReadParamJava)param,
                                         imageMetadata,
                                         this);
        } else
            readState = new J2KReadState(iis,
                                         (J2KImageReadParamJava)param,
                                         this);

        Raster ras = readState.readAsRaster();
        if (abortRequested())
            processReadAborted();
        else
            processImageComplete();
        return ras;
    }

    public boolean isImageTiled(int imageIndex) {
        checkIndex(imageIndex);
        readHeader();
        if (readState != null) {
            RenderedImage image = new J2KRenderedImage(readState);
            if (image.getNumXTiles() * image.getNumYTiles() > 0)
                return true;
            return false;
        }
        return false;
    }

    public void reset() {
        // reset local Java structures
        super.reset();

        iis = null;
        gotHeader = false;
        imageMetadata = null;
        readState = null;
        System.gc();
    }

    /** This method wraps the protected method <code>abortRequested</code>
     *  to allow the abortions be monitored by <code>J2KReadState</code>.
     */
    public boolean getAbortRequest() {
        return abortRequested();
    }

    private ImageTypeSpecifier getImageType(int imageIndex)
        throws IOException {
        checkIndex(imageIndex);
        readHeader();
        if (readState != null) {
            return new ImageTypeSpecifier(readState.getColorModel(),
                                          readState.getSampleModel());
        }
        return null;
    }
}