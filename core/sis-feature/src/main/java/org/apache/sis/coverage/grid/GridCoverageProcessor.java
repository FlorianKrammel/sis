/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sis.coverage.grid;

import java.util.Objects;
import org.opengis.util.FactoryException;
import org.opengis.referencing.operation.TransformException;
import org.apache.sis.image.ImageProcessor;
import org.apache.sis.image.Interpolation;
import org.apache.sis.util.ArgumentChecks;
import org.apache.sis.util.logging.Logging;
import org.apache.sis.util.collection.WeakHashSet;
import org.apache.sis.internal.system.Modules;


/**
 * A predefined set of operations on grid coverages as convenience methods.
 *
 * <h2>Thread-safety</h2>
 * {@code GridCoverageProcessor} is thread-safe if its configuration is not modified after construction.
 *
 * @author  Martin Desruisseaux (Geomatys)
 * @version 1.1
 *
 * @see org.apache.sis.image.ImageProcessor
 *
 * @since 1.1
 * @module
 */
public class GridCoverageProcessor implements Cloneable {
    /**
     * Configured {@link ImageProcessor} instances used by {@link GridCoverage}s created by processors.
     * We use this set for sharing common instances in {@link GridCoverage} instances, which is okay
     * provided that we do not modify the {@link ImageProcessor} configuration.
     */
    private static final WeakHashSet<ImageProcessor> PROCESSORS = new WeakHashSet<>(ImageProcessor.class);

    /**
     * Returns an unique instance of the given processor. Both the given and the returned processors
     * shall be unmodified, because they may be shared by many {@link GridCoverage} instances.
     */
    static ImageProcessor unique(final ImageProcessor image) {
        return PROCESSORS.unique(image);
    }

    /**
     * The image processor to use for resampling operations.
     */
    private final ImageProcessor imageProcessor;

    /**
     * Creates a new set of grid coverage operations with default configuration.
     */
    public GridCoverageProcessor() {
        imageProcessor = new ImageProcessor();
    }

    /**
     * Creates a new set of grid coverage operations with the given configuration.
     *
     * @param  processor  the processor to use for image operations.
     */
    public GridCoverageProcessor(final ImageProcessor processor) {
        ArgumentChecks.ensureNonNull("processor", processor);
        imageProcessor = processor;
    }
    /**
     * Returns the interpolation method to use for resampling operations.
     *
     * @return interpolation method to use in resampling operations.
     */
    public Interpolation getInterpolation() {
        return imageProcessor.getInterpolation();
    }

    /**
     * Sets the interpolation method to use for resampling operations.
     *
     * @param  method  interpolation method to use in resampling operations.
     */
    public void setInterpolation(final Interpolation method) {
        imageProcessor.setInterpolation(method);
    }

    /**
     * Creates a new coverage with a different grid extent, resolution or coordinate reference system.
     * The desired properties are specified by the {@link GridGeometry} argument, which may be incomplete.
     * The missing grid geometry components are completed as below:
     *
     * <table class="sis">
     *   <caption>Default values for undefined grid geometry components</caption>
     *   <tr>
     *     <th>Component</th>
     *     <th>Default value</th>
     *   </tr><tr>
     *     <td>{@linkplain GridGeometry#getExtent() Grid extent}</td>
     *     <td>A default size preserving resolution at source
     *       {@linkplain GridExtent#getPointOfInterest() point of interest}.</td>
     *   </tr><tr>
     *     <td>{@linkplain GridGeometry#getGridToCRS Grid to CRS transform}</td>
     *     <td>Whatever it takes for fitting data inside the supplied extent.</td>
     *   </tr><tr>
     *     <td>{@linkplain GridGeometry#getCoordinateReferenceSystem() Coordinate reference system}</td>
     *     <td>Same as source coverage.</td>
     *   </tr>
     * </table>
     *
     * The interpolation method can be specified by {@link #setInterpolation(Interpolation)}.
     *
     * @param  source  the grid coverage to resample.
     * @param  target  the desired geometry of returned grid coverage. May be incomplete.
     * @return a grid coverage with the characteristics specified in the given grid geometry.
     * @throws IncompleteGridGeometryException if the source grid geometry is missing an information.
     *         It may be the source CRS, the source extent, <i>etc.</i> depending on context.
     * @throws TransformException if some coordinates can not be transformed to the specified target.
     */
    public GridCoverage resample(GridCoverage source, final GridGeometry target) throws TransformException {
        ArgumentChecks.ensureNonNull("source", source);
        ArgumentChecks.ensureNonNull("target", target);
        /*
         * If the source coverage is already the result of a previous "resample" operation,
         * use the original data in order to avoid interpolating values that are already interpolated.
         */
        if (source instanceof ResampledGridCoverage) {
            source = ((ResampledGridCoverage) source).source;
        }
        try {
            return ResampledGridCoverage.create(source, target, imageProcessor);
        } catch (IllegalGridGeometryException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof TransformException) {
                throw (TransformException) cause;
            } else {
                throw e;
            }
        } catch (FactoryException e) {
            throw new TransformException(e);
        }
    }

    /**
     * Invoked when an ignorable exception occurred.
     *
     * @param  caller  the method where the exception occurred.
     * @param  ex      the ignorable exception.
     */
    static void recoverableException(final String caller, final Exception ex) {
        Logging.recoverableException(Logging.getLogger(Modules.RASTER), GridCoverageProcessor.class, caller, ex);
    }

    /**
     * Returns {@code true} if the given object is a coverage processor
     * of the same class with the same configuration.
     *
     * @param  object  the other object to compare with this processor.
     * @return whether the other object is a coverage processor of the same class with the same configuration.
     */
    @Override
    public boolean equals(final Object object) {
        if (object != null && object.getClass() == getClass()) {
            final GridCoverageProcessor other = (GridCoverageProcessor) object;
            return imageProcessor.equals(other.imageProcessor);
        }
        return false;
    }

    /**
     * Returns a hash code value for this coverage processor based on its current configuration.
     *
     * @return a hash code value for this processor.
     */
    @Override
    public int hashCode() {
        return Objects.hash(getClass(), imageProcessor);
    }

    /**
     * Returns a coverage processor with the same configuration than this processor.
     *
     * @return a clone of this image processor.
     */
    @Override
    public GridCoverageProcessor clone() {
        try {
            return (GridCoverageProcessor) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }
}
