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
package org.apache.sis.image;

import java.util.Map;
import java.util.List;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;
import java.util.logging.Filter;
import java.util.logging.LogRecord;
import java.awt.Color;
import java.awt.Shape;
import java.awt.Rectangle;
import java.awt.image.ColorModel;
import java.awt.image.SampleModel;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.awt.image.ImagingOpException;
import java.awt.image.IndexColorModel;
import javax.measure.Quantity;
import org.apache.sis.coverage.Category;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.NoninvertibleTransformException;
import org.apache.sis.referencing.operation.transform.MathTransforms;
import org.apache.sis.coverage.SampleDimension;
import org.apache.sis.math.Statistics;
import org.apache.sis.util.ArraysExt;
import org.apache.sis.util.ArgumentChecks;
import org.apache.sis.util.collection.WeakHashSet;
import org.apache.sis.internal.system.Modules;
import org.apache.sis.internal.coverage.j2d.TiledImage;
import org.apache.sis.internal.feature.Resources;
import org.apache.sis.measure.NumberRange;
import org.apache.sis.measure.Units;


/**
 * A predefined set of operations on images as convenience methods.
 * After instantiation, {@code ImageProcessor} can be configured for the following aspects:
 *
 * <ul class="verbose">
 *   <li>
 *     {@linkplain #setInterpolation(Interpolation) Interpolation method} to use during resampling operations.
 *   </li><li>
 *     {@linkplain #setFillValues(Number...) Fill values} to use for pixels that can not be computed.
 *   </li><li>
 *     Whether operations can be executed in parallel. By default operations on unknown
 *     {@link RenderedImage} implementations are executed sequentially in the caller thread, for safety reasons.
 *     Some operations can be parallelized, but it should be enabled only if the {@link RenderedImage} is known
 *     to be thread-safe and has concurrent (or fast) implementation of {@link RenderedImage#getTile(int, int)}.
 *     Apache SIS implementations of {@link RenderedImage} can be parallelized, but it may not be the case of
 *     images from other libraries.
 *   </li><li>
 *     Whether the operations should fail if an exception is thrown while processing a tile.
 *     By default errors during calculation are propagated as an {@link ImagingOpException},
 *     in which case no result is available. But errors can also be notified as a {@link LogRecord} instead,
 *     in which case partial results may be available.
 *   </li>
 * </ul>
 *
 * <h2>Area of interest</h2>
 * Some operations accept an optional <cite>area of interest</cite> argument specified as a {@link Shape} instance in
 * pixel coordinates. If a shape is given, it should not be modified after {@code ImageProcessor} method call because
 * the given object may be retained directly (i.e. the {@code Shape} is not always cloned; it depends on its class).
 * In addition, the {@code Shape} implementation shall be thread-safe (assuming its state stay unmodified)
 * unless the execution mode is set to {@link Mode#PARALLEL}.
 *
 * <h2>Error handling</h2>
 * If an exception occurs during the computation of a tile, then the {@code ImageProcessor} behavior
 * is controlled by the {@link #getErrorAction() errorAction} property:
 *
 * <ul>
 *   <li>If {@link ErrorAction#THROW}, the exception is wrapped in an {@link ImagingOpException} and thrown.</li>
 *   <li>If {@link ErrorAction#LOG}, the exception is logged and a partial result is returned.</li>
 *   <li>If any other value, the exception is wrapped in a {@link LogRecord} and sent to that filter.
 *     The filter can store the log record, for example for showing later in a graphical user interface (GUI).
 *     If the filter returns {@code true}, the log record is also logged, otherwise it is silently discarded.
 *     In both cases a partial result is returned.</li>
 * </ul>
 *
 * <h2>Thread-safety</h2>
 * {@code ImageProcessor} is safe for concurrent use in multi-threading environment.
 *
 * @author  Martin Desruisseaux (Geomatys)
 * @version 1.1
 *
 * @see org.apache.sis.coverage.grid.GridCoverageProcessor
 *
 * @since 1.1
 * @module
 */
public class ImageProcessor implements Cloneable {
    /**
     * Cache of previously created images. We use this cache only for images of known implementations,
     * especially the ones which may be costly to compute. Reusing an existing instance avoid to repeat
     * the computation.
     */
    private static final WeakHashSet<RenderedImage> CACHE = new WeakHashSet<>(RenderedImage.class);

    /**
     * Returns an unique instance of the given image. This method should be invoked only for images
     * of known implementation, especially the ones which are costly to compute. The implementation
     * shall override {@link Object#equals(Object)} and {@link Object#hashCode()} methods.
     */
    static RenderedImage unique(final RenderedImage image) {
        return CACHE.unique(image);
    }

    /**
     * Interpolation to use during resample operations.
     *
     * @see #getInterpolation()
     * @see #setInterpolation(Interpolation)
     */
    private Interpolation interpolation;

    /**
     * The values to use for pixels that can not be computed.
     * This array may be {@code null} or may contain {@code null} elements.
     * This is a "copy on write" array (elements are not modified).
     *
     * @see #getFillValues()
     * @see #setFillValues(Number...)
     */
    private Number[] fillValues;

    /**
     * Whether the operations can be executed in parallel.
     *
     * @see #getExecutionMode()
     * @see #setExecutionMode(Mode)
     */
    private Mode executionMode;

    /**
     * Execution modes specifying whether operations can be executed in parallel.
     * If {@link #SEQUENTIAL}, operations are executed sequentially in the caller thread.
     * If {@link #PARALLEL}, some operations may be parallelized using an arbitrary number of threads.
     *
     * @see #getExecutionMode()
     * @see #setExecutionMode(Mode)
     */
    public enum Mode {
        /**
         * Operations executed in multi-threaded mode if possible.
         * This mode can be used if the {@link RenderedImage} instances are thread-safe and provide
         * a concurrent (or very fast) implementation of {@link RenderedImage#getTile(int, int)}.
         */
        PARALLEL,

        /**
         * Operations executed in the caller thread, without parallelization.
         * Sequential operations may be useful for processing {@link RenderedImage}
         * implementations that may not be thread-safe.
         */
        SEQUENTIAL,

        /**
         * Operations are executed in multi-threaded mode if the {@link RenderedImage} instance
         * is an implementation known to be thread-safe. All operations on image implementations
         * unknown to Apache SIS are executed in sequential mode.
         */
        DEFAULT
    }

    /**
     * Hints about the desired positional accuracy (in "real world" units or in pixel units),
     * or {@code null} if unspecified. In order to avoid the need to clone this array in the
     * {@link #clone()} method, the content of this array should not be modified.
     * For setting new values, a new array should be created.
     *
     * @see #getPositionalAccuracyHints()
     * @see #setPositionalAccuracyHints(Quantity...)
     */
    private Quantity<?>[] positionalAccuracyHints;

    /**
     * Whether errors occurring during computation should be propagated or wrapped in a {@link LogRecord}.
     * If errors are wrapped in a {@link LogRecord}, this field specifies what to do with the record.
     * Only one log record is created for all tiles that failed for the same operation on the same image.
     *
     * @see #getErrorAction()
     * @see #setErrorAction(Filter)
     */
    private Filter errorAction;

    /**
     * Specifies how exceptions occurring during calculation should be handled.
     * This enumeration provides common actions, but the set of values that can
     * be specified to {@link #setErrorAction(Filter)} is not limited to this enumeration.
     *
     * @see #getErrorAction()
     * @see #setErrorAction(Filter)
     */
    public enum ErrorAction implements Filter {
        /**
         * Exceptions are wrapped in an {@link ImagingOpException} and thrown.
         * In such case, no result is available. This is the default action.
         */
        THROW,

        /**
         * Exceptions are wrapped in a {@link LogRecord} and logged at {@link java.util.logging.Level#WARNING}.
         * Only one log record is created for all tiles that failed for the same operation on the same image.
         * A partial result may be available.
         *
         * <p>Users are encouraged to use {@link #THROW} or to specify their own {@link Filter}
         * instead than using this error action, because not everyone read logging records.</p>
         */
        LOG;

        /**
         * Unconditionally returns {@code true} for allowing the given record to be logged.
         * This method is not useful for this {@code ErrorAction} enumeration, but is useful
         * for other instances given to {@link #setErrorAction(Filter)}.
         *
         * @param  record  the error that occurred during computation of a tile.
         * @return always {@code true}.
         */
        @Override
        public boolean isLoggable(final LogRecord record) {
            return true;
        }
    }

    /**
     * Creates a new processor with default configuration.
     * The execution mode is initialized to {@link Mode#DEFAULT} and the error action to {@link ErrorAction#THROW}.
     */
    public ImageProcessor() {
        executionMode = Mode.DEFAULT;
        errorAction   = ErrorAction.THROW;
        interpolation = Interpolation.BILINEAR;
    }

    /**
     * Returns the interpolation method to use during resample operations.
     *
     * @return interpolation method to use during resample operations.
     *
     * @see #resample(RenderedImage, Rectangle, MathTransform)
     */
    public synchronized Interpolation getInterpolation() {
        return interpolation;
    }

    /**
     * Sets the interpolation method to use during resample operations.
     *
     * @param  method  interpolation method to use during resample operations.
     *
     * @see #resample(RenderedImage, Rectangle, MathTransform)
     */
    public synchronized void setInterpolation(final Interpolation method) {
        ArgumentChecks.ensureNonNull("method", method);
        interpolation = method;
    }

    /**
     * Returns the values to use for pixels that can not be computed.
     * This method returns a copy of the array set by the last call to {@link #setFillValues(Number...)}.
     *
     * @return fill values to use for pixels that can not be computed, or {@code null} for the defaults.
     */
    public synchronized Number[] getFillValues() {
        return (fillValues != null) ? fillValues.clone() : null;
    }

    /**
     * Sets the values to use for pixels that can not be computed. The given array may be {@code null} or may contain
     * {@code null} elements for default values. Those defaults are zero for images storing sample values as integers,
     * or {@link Float#NaN} or {@link Double#NaN} for images storing sample values as floating point numbers. If the
     * given array contains less elements than the number of bands in an image, missing elements will be assumed null.
     * If the given array contains more elements than the number of bands, extraneous elements will be ignored.
     *
     * @param  values  fill values to use for pixels that can not be computed, or {@code null} for the defaults.
     */
    public synchronized void setFillValues(final Number... values) {
        fillValues = (values != null) ? values.clone() : null;
    }

    /**
     * Returns whether operations can be executed in parallel.
     * If {@link Mode#SEQUENTIAL}, operations are executed sequentially in the caller thread.
     * If {@link Mode#PARALLEL}, some operations may be parallelized using an arbitrary number of threads.
     *
     * @return whether the operations can be executed in parallel.
     */
    public synchronized Mode getExecutionMode() {
        return executionMode;
    }

    /**
     * Sets whether operations can be executed in parallel.
     * This value can be set to {@link Mode#PARALLEL} if the {@link RenderedImage} instances are thread-safe
     * and provide a concurrent (or very fast) implementation of {@link RenderedImage#getTile(int, int)}.
     * If {@link Mode#SEQUENTIAL}, only the caller thread is used. Sequential operations may be useful
     * for processing {@link RenderedImage} implementations that may not be thread-safe.
     *
     * <p>It is safe to set this flag to {@link Mode#PARALLEL} with {@link java.awt.image.BufferedImage}
     * (it will actually have no effect in this particular case) or with Apache SIS implementations of
     * {@link RenderedImage}.</p>
     *
     * @param  mode  whether the operations can be executed in parallel.
     */
    public synchronized void setExecutionMode(final Mode mode) {
        ArgumentChecks.ensureNonNull("mode", mode);
        executionMode = mode;
    }

    /**
     * Whether the operations can be executed in parallel for the specified image.
     * This method shall be invoked in a method synchronized on {@code this}.
     */
    private boolean parallel(final RenderedImage source) {
        assert Thread.holdsLock(this);
        switch (executionMode) {
            case PARALLEL:   return true;
            case SEQUENTIAL: return false;
            default:         return source.getClass().getName().startsWith(Modules.CLASSNAME_PREFIX);
        }
    }

    /**
     * Returns hints about the desired positional accuracy, in "real world" units or in pixel units.
     * This is an empty array by default, which means that {@code ImageProcessor} aims for the best
     * accuracy it can produce. If the returned array is non-empty and contains accuracies large enough,
     * {@code ImageProcessor} may use some slightly faster algorithms at the expense of accuracy.
     *
     * @return desired accuracy in no particular order, or an empty array if none.
     */
    public synchronized Quantity<?>[] getPositionalAccuracyHints() {
        return (positionalAccuracyHints != null) ? positionalAccuracyHints.clone() : new Quantity<?>[0];
    }

    /**
     * Sets hints about desired positional accuracy, in "real world" units or in pixel units.
     * More than one hint can be specified for allowing the use of different units.
     * For example the given array can contain an accuracy in metres and an accuracy in seconds,
     * for specifying desired accuracies in both spatial dimensions and in the temporal dimension.
     * Accuracy can also be specified in both real world units such as {@linkplain Units#METRE metres}
     * and in {@linkplain Units#PIXEL pixel units}, which are converted to real world units depending
     * on image resolution. If more than one value is applicable to a dimension
     * (after unit conversion if needed), the smallest value is taken.
     *
     * <p>Those values are only hints, the {@code ImageProcessor} is free to ignore them.
     * In any cases there is no guarantees that computed images will met those accuracies.
     * The given values are honored on a <em>best effort</em> basis only.</p>
     *
     * <p>In current implementation, {@code ImageProcessor} recognizes only accuracies in {@link Units#PIXEL}.
     * A value such as 0.125 pixel may cause {@code ImageProcessor} to use some a slightly faster algorithm
     * at the expense of accuracy during {@linkplain #resample resampling operations}.</p>
     *
     * @param  hints  desired accuracy in no particular order, or a {@code null} array if none.
     *                Null elements in the array are ignored.
     */
    public synchronized void setPositionalAccuracyHints(final Quantity<?>... hints) {
        if (hints != null) {
            final Quantity<?>[] copy = new Quantity<?>[hints.length];
            int n = 0;
            for (final Quantity<?> hint : hints) {
                if (hint != null) copy[n++] = hint;
            }
            if (n != 0) {
                positionalAccuracyHints = ArraysExt.resize(copy, n);
                return;
            }
        }
        positionalAccuracyHints = null;
    }

    /**
     * Returns whether exceptions occurring during computation are propagated or logged.
     * If {@link ErrorAction#THROW} (the default), exceptions are wrapped in {@link ImagingOpException} and thrown.
     * If any other value, exceptions are wrapped in a {@link LogRecord}, filtered then eventually logged.
     *
     * @return whether exceptions occurring during computation are propagated or logged.
     */
    public synchronized Filter getErrorAction() {
        return errorAction;
    }

    /**
     * Sets whether exceptions occurring during computation are propagated or logged.
     * The default behavior is to wrap exceptions in {@link ImagingOpException} and throw them.
     * If this property is set to {@link ErrorAction#LOG} or any other value, then exceptions will
     * be wrapped in {@link LogRecord} instead, in which case a partial result may be available.
     * Only one log record is created for all tiles that failed for the same operation on the same image.
     *
     * @param  action  filter to notify when an operation failed on one or more tiles,
     *                 or {@link ErrorAction#THROW} for propagating the exception.
     */
    public synchronized void setErrorAction(final Filter action) {
        ArgumentChecks.ensureNonNull("action", action);
        errorAction = action;
    }

    /**
     * Whether errors occurring during computation should be propagated instead than wrapped in a {@link LogRecord}.
     * This method shall be invoked in a method synchronized on {@code this}.
     */
    private boolean failOnException() {
        assert Thread.holdsLock(this);
        return errorAction == ErrorAction.THROW;
    }

    /**
     * Where to send exceptions (wrapped in {@link LogRecord}) if an operation failed on one or more tiles.
     * Only one log record is created for all tiles that failed for the same operation on the same image.
     * This is always {@code null} if {@link #failOnException()} is {@code true}.
     * This method shall be invoked in a method synchronized on {@code this}.
     */
    private Filter errorListener() {
        assert Thread.holdsLock(this);
        return (errorAction instanceof ErrorAction) ? null : errorAction;
    }

    /**
     * Returns statistics (minimum, maximum, mean, standard deviation) on each bands of the given image.
     * Invoking this method is equivalent to invoking {@link #statistics(RenderedImage, Shape)} and
     * extracting immediately the statistics property value, except that errors are handled by the
     * {@linkplain #getErrorAction() error handler}.
     *
     * <p>If {@code areaOfInterest} is {@code null}, then the default is as below:</p>
     * <ul>
     *   <li>If the {@value StatisticsCalculator#STATISTICS_KEY} property value exists in the given image,
     *       then that value is returned. Note that they are not necessarily statistics for the whole image.
     *       They are whatever statistics the property provided considered as representative.</li>
     *   <li>Otherwise statistics are computed for the whole image.</li>
     * </ul>
     *
     * @param  source          the image for which to compute statistics.
     * @param  areaOfInterest  pixel coordinates of the area of interest, or {@code null} for the default.
     * @return the statistics of sample values in each band.
     * @throws ImagingOpException if an error occurred during calculation
     *         and the error handler is {@link ErrorAction#THROW}.
     *
     * @see #statistics(RenderedImage, Shape)
     * @see StatisticsCalculator#STATISTICS_KEY
     */
    public Statistics[] getStatistics(final RenderedImage source, final Shape areaOfInterest) {
        ArgumentChecks.ensureNonNull("source", source);
        if (areaOfInterest == null) {
            final Object property = source.getProperty(StatisticsCalculator.STATISTICS_KEY);
            if (property instanceof Statistics[]) {
                return (Statistics[]) property;
            }
        }
        final boolean parallel, failOnException;
        final Filter errorListener;
        synchronized (this) {
            parallel        = parallel(source);
            failOnException = failOnException();
            errorListener   = errorListener();
        }
        /*
         * No need to check if the given source is already an instance of StatisticsCalculator.
         * The way AnnotatedImage cache mechanism is implemented, if statistics results already
         * exist, they will be used.
         */
        final AnnotatedImage calculator = new StatisticsCalculator(source, areaOfInterest, parallel, failOnException);
        final Object property = calculator.getProperty(StatisticsCalculator.STATISTICS_KEY);
        calculator.logAndClearError(ImageProcessor.class, "getStatistics", errorListener);
        return (Statistics[]) property;
    }

    /**
     * Returns an image with statistics (minimum, maximum, mean, standard deviation) on each bands.
     * The property value will be computed when first requested (it is not computed immediately by this method).
     *
     * <p>If {@code areaOfInterest} is {@code null}, then the default is as below:</p>
     * <ul>
     *   <li>If the {@value StatisticsCalculator#STATISTICS_KEY} property value exists in the given image,
     *       then that image is returned as-is. Note that the existing property value is not necessarily
     *       statistics for the whole image.
     *       They are whatever statistics the property provider considers as representative.</li>
     *   <li>Otherwise an image augmented with a {@value StatisticsCalculator#STATISTICS_KEY} property value
     *       is returned.</li>
     * </ul>
     *
     * @param  source          the image for which to provide statistics.
     * @param  areaOfInterest  pixel coordinates of the area of interest, or {@code null} for the default.
     * @return an image with an {@value StatisticsCalculator#STATISTICS_KEY} property.
     *         May be {@code image} if the given argument already has a statistics property.
     *
     * @see #getStatistics(RenderedImage, Shape)
     * @see StatisticsCalculator#STATISTICS_KEY
     */
    public RenderedImage statistics(final RenderedImage source, final Shape areaOfInterest) {
        ArgumentChecks.ensureNonNull("source", source);
        if (areaOfInterest == null && ArraysExt.contains(source.getPropertyNames(), StatisticsCalculator.STATISTICS_KEY)) {
            return source;
        }
        final boolean parallel, failOnException;
        synchronized (this) {
            parallel        = parallel(source);
            failOnException = failOnException();
        }
        return new StatisticsCalculator(source, areaOfInterest, parallel, failOnException).unique();
    }

    /**
     * Returns an image with the same sample values than the given image, but with its color ramp stretched between
     * specified or inferred bounds. For example in a gray scale image, pixels with the minimum value will be black
     * and pixels with the maximum value will be white. This operation is a kind of <cite>tone mapping</cite>,
     * a technique used in image processing to map one set of colors to another. The mapping applied by this method
     * is conceptually a simple linear transform (a scale and an offset) applied on sample values before they are
     * mapped to their colors.
     *
     * <p>The minimum and maximum value can be either specified explicitly,
     * or determined from {@linkplain #getStatistics(RenderedImage, Shape) statistics} on the image.
     * In the later case a range of value is determined first from the {@linkplain Statistics#minimum() minimum}
     * and {@linkplain Statistics#maximum() maximum} values found in the image, optionally narrowed to an interval
     * of some {@linkplain Statistics#standardDeviation(boolean) standard deviations} around the mean value.</p>
     *
     * <p>Narrowing with standard deviations is useful for data having a Gaussian distribution, as often seen in nature.
     * In such distribution, 99.9% of the data are between the mean ± 3×<var>standard deviation</var>, but some values
     * may still appear much further. The minimum and maximum values alone are not a robust way to compute a range of
     * values for the color ramp because a single value very far from other values is sufficient for making the colors
     * difficult to distinguish for 99.9% of the data.</p>
     *
     * <p>The range of values for the color ramp can be narrowed with following modifiers
     * (a {@link Map} is used for allowing addition of more modifiers in future Apache SIS versions).
     * All unrecognized modifiers are silently ignored. If no modifier is specified, then the color ramp
     * will be stretched from minimum to maximum values found in specified image.</p>
     *
     * <table>
     *   <caption>Value range modifiers</caption>
     *   <tr>
     *     <th>Key</th>
     *     <th>Purpose</th>
     *     <th>Values</th>
     *   </tr><tr>
     *     <td>{@code "minimum"}</td>
     *     <td>Minimum value (omit for computing from statistics).</td>
     *     <td>{@link Number}</td>
     *   </tr><tr>
     *     <td>{@code "maximum"}</td>
     *     <td>Maximum value (omit for computing from statistics).</td>
     *     <td>{@link Number}</td>
     *   </tr><tr>
     *     <td>{@code "multStdDev"}</td>
     *     <td>Multiple of the standard deviation.</td>
     *     <td>{@link Number} (typical values: 1.5, 2 or 3)</td>
     *   </tr><tr>
     *     <td>{@code "statistics"}</td>
     *     <td>Statistics or image from which to get statistics.</td>
     *     <td>{@link Statistics} or {@link RenderedImage}</td>
     *   </tr><tr>
     *     <td>{@code "areaOfInterest"}</td>
     *     <td>Pixel coordinates of the region for which to compute statistics.</td>
     *     <td>{@link Shape}</td>
     *   </tr>
     * </table>
     *
     * <h4>Limitation</h4>
     * Current implementation can stretch only gray scale images (it may be extended to indexed color models
     * in a future version). If this method can not stretch the color ramp, for example because the given image
     * is an RGB image, then the image is returned unchanged.
     *
     * @param  source     the image to recolor.
     * @param  modifiers  modifiers for narrowing the range of values, or {@code null} if none.
     * @return the image with color ramp stretched between the specified or calculated bounds,
     *         or {@code image} unchanged if the operation can not be applied on the given image.
     */
    public RenderedImage stretchColorRamp(final RenderedImage source, final Map<String,?> modifiers) {
        ArgumentChecks.ensureNonNull("source", source);
        return RecoloredImage.stretchColorRamp(this, source, modifiers);
    }

    /**
     * Returns an image where all sample values are indices of colors in an {@link IndexColorModel}.
     * If the given image stores sample values as unsigned bytes or short integers, then those values
     * are used as-is (they are not copied or converted). Otherwise this operation will convert sample
     * values to unsigned bytes in order to enable the use of {@link IndexColorModel}.
     *
     * <p>The given map specifies the color to use for different ranges of values in the source image.
     * The ranges of values in the returned image may not be the same; this method is free to rescale them.
     * The {@link Color} arrays may have any length; colors will be interpolated as needed for fitting
     * the ranges of values in the destination image.</p>
     *
     * <p>The resulting image is suitable for visualization purposes, but should not be used for computation
     * purposes. There is no guarantees about the number of bands in returned image and the formulas used for
     * converting floating point values to integer values.</p>
     *
     * @param  source  the image to recolor for visualization purposes.
     * @param  colors  colors to use for each range of values in the source image.
     * @return recolored image for visualization purposes only.
     */
    public RenderedImage toIndexedColors(final RenderedImage source, final Map<NumberRange<?>,Color[]> colors) {
        ArgumentChecks.ensureNonNull("source", source);
        ArgumentChecks.ensureNonNull("colors", colors);
        try {
            return RecoloredImage.toIndexedColors(this, source, null, null, colors.entrySet());
        } catch (IllegalStateException | NoninvertibleTransformException e) {
            throw new IllegalArgumentException(Resources.format(Resources.Keys.UnconvertibleSampleValues), e);
        }
    }

    /**
     * Returns an image where all sample values are indices of colors in an {@link IndexColorModel}.
     * If the given image stores sample values as unsigned bytes or short integers, then those values
     * are used as-is (they are not copied or converted). Otherwise this operation will convert sample
     * values to unsigned bytes in order to enable the use of {@link IndexColorModel}.
     *
     * <p>This method is similar to {@link #toIndexedColors(RenderedImage, Map)}
     * except that the {@link Map} argument is splitted in two parts: the ranges (map keys) are
     * {@linkplain Category#getSampleRange() encapsulated in <code>Category</code>} objects, themselves
     * {@linkplain SampleDimension#getCategories() encapsulated in <code>SampleDimension</code>} objects.
     * The colors (map values) are determined by a function receiving {@link Category} inputs.
     * This separation makes easier to apply colors based on criterion other than numerical values.
     * For example colors could be determined from {@linkplain Category#getName() category name} such as "Temperature",
     * or {@linkplain org.apache.sis.measure.MeasurementRange#unit() units of measurement}.
     * The {@link Color} arrays may have any length; colors will be interpolated as needed for fitting
     * the ranges of values in the destination image.</p>
     *
     * <p>The resulting image is suitable for visualization purposes, but should not be used for computation
     * purposes. There is no guarantees about the number of bands in returned image and the formulas used for
     * converting floating point values to integer values.</p>
     *
     * @param  source  the image to recolor for visualization purposes.
     * @param  ranges  description of {@code source} bands, or {@code null} if none. This is typically
     *                 obtained by {@link org.apache.sis.coverage.grid.GridCoverage#getSampleDimensions()}.
     * @param  colors  the colors to use for given categories. This function can return {@code null} or
     *                 empty arrays for some categories, which are interpreted as fully transparent pixels.
     * @return recolored image for visualization purposes only.
     */
    public RenderedImage toIndexedColors(final RenderedImage source,
            final List<SampleDimension> ranges, final Function<Category,Color[]> colors)
    {
        ArgumentChecks.ensureNonNull("source", source);
        ArgumentChecks.ensureNonNull("colors", colors);
        try {
            return RecoloredImage.toIndexedColors(this, source, ranges, colors, null);
        } catch (IllegalStateException | NoninvertibleTransformException e) {
            throw new IllegalArgumentException(Resources.format(Resources.Keys.UnconvertibleSampleValues), e);
        }
    }

    /**
     * Selects a subset of bands in the given image. This method can also be used for changing band order
     * or repeating the same band from the source image. If the specified {@code bands} are the same than
     * the source image bands in the same order, then {@code source} is returned directly.
     *
     * <p>This method returns an image sharing the same data buffer than the source image;
     * pixel values are not copied. Consequently changes in the source image are reflected
     * immediately in the returned image.</p>
     *
     * @param  source  the image in which to select bands.
     * @param  bands   indices of bands to retain.
     * @return image width selected bands.
     * @throws IllegalArgumentException if a band index is invalid.
     */
    public RenderedImage selectBands(final RenderedImage source, final int... bands) {
        ArgumentChecks.ensureNonNull("source", source);
        return BandSelectImage.create(source, bands);
    }

    /**
     * Creates a new image which will resample the given image. The resampling operation is defined
     * by a non-linear transform from the <em>new</em> image to the specified <em>source</em> image.
     * That transform should map {@linkplain org.opengis.referencing.datum.PixelInCell#CELL_CENTER pixel centers}.
     * If that transform produces coordinates that are outside source envelope bounds, then the corresponding pixels
     * in the new image are set to {@linkplain #getFillValues() fill values}. Otherwise sample values are interpolated
     * using the method given by {@link #getInterpolation()}.
     *
     * <p>If the given source is an instance of {@link ResampledImage},
     * then this method will use {@linkplain PlanarImage#getSources() the source} of the given source.
     * The intent is to avoid resampling a resampled image; instead this method tries to work on the original data.</p>
     *
     * @param  source    the image to be resampled.
     * @param  bounds    domain of pixel coordinates of resampled image to create.
     * @param  toSource  conversion of pixel coordinates from resampled image to {@code source} image.
     * @return resampled image (may be {@code source}).
     */
    public RenderedImage resample(RenderedImage source, final Rectangle bounds, MathTransform toSource) {
        ArgumentChecks.ensureNonNull("source",   source);
        ArgumentChecks.ensureNonNull("bounds",   bounds);
        ArgumentChecks.ensureNonNull("toSource", toSource);
        final ColorModel  cm = source.getColorModel();
        final SampleModel sm = source.getSampleModel();
        boolean isIdentity = toSource.isIdentity();
        RenderedImage resampled = null;
        for (;;) {
            if (isIdentity && bounds.x == source.getMinX() && bounds.y == source.getMinY() &&
                    bounds.width == source.getWidth() && bounds.height == source.getHeight())
            {
                resampled = source;
                break;
            }
            if (Objects.equals(sm, source.getSampleModel())) {
                if (source instanceof ImageAdapter) {
                    source = ((ImageAdapter) source).source;
                    continue;
                }
                if (source instanceof ResampledImage) {
                    final List<RenderedImage> sources = source.getSources();
                    if (sources != null && sources.size() == 1) {                         // Paranoiac check.
                        toSource   = MathTransforms.concatenate(toSource, ((ResampledImage) source).toSource);
                        isIdentity = toSource.isIdentity();
                        source     = sources.get(0);
                        continue;
                    }
                }
            }
            /*
             * All accesses to ImageProcessor fields done by this method should be isolated in this single
             * synchronized block. All arrays are "copy on write", so they do not need to be cloned.
             */
            final Interpolation interpolation;
            final Number[]      fillValues;
            final Quantity<?>[] positionalAccuracyHints;
            synchronized (this) {
                interpolation           = this.interpolation;
                fillValues              = this.fillValues;
                positionalAccuracyHints = this.positionalAccuracyHints;
            }
            resampled = unique(new ResampledImage(source, bounds, toSource,
                    interpolation, fillValues, positionalAccuracyHints));
            break;
        }
        return RecoloredImage.create(resampled, cm);
    }

    /**
     * Computes immediately all tiles in the given region of interest, then return an image will those tiles ready.
     * Computations will use many threads if {@linkplain #getExecutionMode() execution mode} is parallel.
     *
     * <div class="note"><b>Note:</b>
     * current implementation ignores the {@linkplain #getErrorAction() error action} because we do not yet
     * have a mechanism for specifying which tile to produce in replacement of tiles that can not be computed.
     * This behavior may be changed in a future version.</div>
     *
     * @param  source          the image to compute immediately (may be {@code null}).
     * @param  areaOfInterest  pixel coordinates of the region to prefetch, or {@code null} for the whole image.
     * @return image with all tiles intersecting the AOI computed, or {@code null} if the given image was null.
     * @throws ImagingOpException if an exception occurred during {@link RenderedImage#getTile(int, int)} call.
     *         This exception wraps the original exception as its {@linkplain ImagingOpException#getCause() cause}.
     */
    public RenderedImage prefetch(RenderedImage source, final Rectangle areaOfInterest) {
        if (source == null || source instanceof BufferedImage || source instanceof TiledImage) {
            return source;
        }
        while (source instanceof PrefetchedImage) {
            source = ((PrefetchedImage) source).source;
        }
        final boolean parallel;
        synchronized (this) {
            parallel = parallel(source);
        }
        final PrefetchedImage image = new PrefetchedImage(source, areaOfInterest, parallel);
        return image.isEmpty() ? source : image;
    }

    /**
     * Returns {@code true} if the given object is an image processor
     * of the same class with the same configuration.
     *
     * @param  object  the other object to compare with this processor.
     * @return whether the other object is an image processor of the same class with the same configuration.
     */
    @Override
    public boolean equals(final Object object) {
        if (object != null && object.getClass() == getClass()) {
            final ImageProcessor other = (ImageProcessor) object;
            final Mode          executionMode;
            final Filter        errorAction;
            final Interpolation interpolation;
            final Number[]      fillValues;
            final Quantity<?>[] positionalAccuracyHints;
            synchronized (this) {
                executionMode           = this.executionMode;
                errorAction             = this.errorAction;
                interpolation           = this.interpolation;
                fillValues              = this.fillValues;
                positionalAccuracyHints = this.positionalAccuracyHints;
            }
            synchronized (other) {
                return errorAction.equals(other.errorAction)     &&
                     executionMode.equals(other.executionMode)   &&
                     interpolation.equals(other.interpolation)   &&
                     Arrays.equals(fillValues, other.fillValues) &&
                     Arrays.equals(positionalAccuracyHints, other.positionalAccuracyHints);
            }
        }
        return false;
    }

    /**
     * Returns a hash code value for this image processor based on its current configuration.
     *
     * @return a hash code value for this processor.
     */
    @Override
    public synchronized int hashCode() {
        return Objects.hash(getClass(), errorAction, executionMode, interpolation)
                + 37 * Arrays.hashCode(fillValues)
                + 39 * Arrays.hashCode(positionalAccuracyHints);
    }

    /**
     * Returns an image processor with the same configuration than this processor.
     *
     * @return a clone of this image processor.
     */
    @Override
    public synchronized ImageProcessor clone() {
        try {
            return (ImageProcessor) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }
}
