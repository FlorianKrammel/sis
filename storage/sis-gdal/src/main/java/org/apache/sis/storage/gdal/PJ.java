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
package org.apache.sis.storage.gdal;

import java.util.Objects;
import java.io.Serializable;
import java.io.ObjectStreamException;
import java.io.InvalidObjectException;
import org.opengis.util.FactoryException;
import org.opengis.util.InternationalString;
import org.opengis.referencing.ReferenceIdentifier;
import org.opengis.metadata.citation.Citation;
import org.opengis.referencing.datum.Ellipsoid;
import org.opengis.referencing.datum.GeodeticDatum;
import org.opengis.referencing.datum.PrimeMeridian;
import org.opengis.referencing.operation.TransformException;
import org.apache.sis.referencing.factory.InvalidGeodeticParameterException;
import org.apache.sis.referencing.IdentifiedObjects;
import org.apache.sis.metadata.iso.citation.Citations;
import org.apache.sis.internal.util.Constants;
import org.apache.sis.internal.system.OS;


/**
 * Wraps the <a href="http://proj.osgeo.org/">{@literal Proj.4}</a> {@code PJ} native data structure.
 * Many methods defined in this class are native methods delegating their work to the Proj.4 library.
 * This class is the only place where such native methods are defined for Proj.4 support.
 *
 * <p>In the Proj.4 library, the {@code PJ} structure is an aggregation of {@link GeodeticDatum},
 * {@link Ellipsoid}, {@link PrimeMeridian}, {@link org.opengis.referencing.cs.CoordinateSystem},
 * {@link org.opengis.referencing.crs.CoordinateReferenceSystem} and their sub-interfaces.
 * The relationship with the GeoAPI methods is indicated in the "See" tags when appropriate.</p>
 *
 * @author  Martin Desruisseaux (Geomatys)
 * @version 0.8
 * @since   0.8
 * @module
 */
@SuppressWarnings("serial")     // serialVersionUID not needed since writeReplace() gives another kind of object.
final class PJ implements ReferenceIdentifier, Serializable {
    /**
     * Loads the {@literal Proj.4} library.
     * This static initializer may throw a {@link UnsatisfiedLinkError} if the static library can not be loaded.
     * In such case, any future attempt to use this {@code PJ} class will cause a {@link NoClassDefFoundError}
     * as per Java language specification.
     */
    static {
        OS.load(PJ.class, "libproj-binding");
    }

    /**
     * The pointer to {@code PJ} structure allocated in the C/C++ heap. This value has no meaning in Java code,
     * except 0 which means no native object. <strong>Do not modify</strong>, since this value is used by Proj.4.
     * Do not rename neither, unless you update accordingly the C code in JNI wrappers.
     */
    private final long ptr;

    /**
     * Creates a new {@code PJ} structure from the given {@literal Proj.4} definition string.
     *
     * @param  definition  the Proj.4 definition string.
     * @throws InvalidGeodeticParameterException if the PJ structure can not be created from the given string.
     */
    public PJ(final String definition) throws InvalidGeodeticParameterException {
        Objects.requireNonNull(definition);
        ptr = allocatePJ(definition);
        if (ptr == 0) {
            // Note: our getLastError() implementation is safe even if pts == 0.
            throw new InvalidGeodeticParameterException(getLastError());
        }
    }

    /**
     * Creates a new {@code PJ} structure for the geographic part of the given {@code PJ} object.
     * This constructor is usually for getting the
     * {@linkplain org.opengis.referencing.crs.ProjectedCRS#getBaseCRS() base geographic CRS}
     * from a {@linkplain org.opengis.referencing.crs.ProjectedCRS projected CRS}.
     *
     * @param  crs   the CRS (usually projected) from which to derive a new CRS.
     * @throws FactoryException if the PJ structure can not be created.
     */
    public PJ(final PJ crs) throws FactoryException {
        Objects.requireNonNull(crs);
        ptr = allocateGeoPJ(crs);
        if (ptr == 0) {
            throw new FactoryException(crs.getLastError());
        }
    }

    /**
     * Allocates a PJ native data structure and returns the pointer to it. This method should be invoked by
     * the constructor only, and the return value <strong>must</strong> be assigned to the {@link #ptr} field.
     * The allocated structure is released by the {@link #finalize()} method.
     *
     * @param  definition  the Proj.4 definition string.
     * @return a pointer to the PJ native data structure, or 0 if the operation failed.
     */
    private static native long allocatePJ(String definition);

    /**
     * Allocates a PJ native data structure for the base geographic CRS of the given CRS, and returns the pointer to it.
     * This method should be invoked by the constructor only, and the return value <strong>must</strong> be assigned to
     * the {@link #ptr} field. The allocated structure is released by the {@link #finalize()} method.
     *
     * @param  projected  the CRS from which to derive the base geographic CRS.
     * @return a pointer to the PJ native data structure, or 0 if the operation failed.
     */
    private static native long allocateGeoPJ(PJ projected);

    /**
     * Returns the project responsible for maintenance of the namespace.
     *
     * @see #getCodeSpace()
     */
    @Override
    public Citation getAuthority() {
        return Citations.PROJ4;
    }

    /**
     * Returns the version identifier for the namespace, as specified by the code authority.
     * This method* parses the Proj.4 release string (for example <cite>"Rel. 4.9.3, 15 August 2016"</cite>)
     * for extracting the version number ("4.9.3" in above example).
     *
     * @see Proj4#version()
     */
    @Override
    public String getVersion() {
        String rel = getRelease();
        if (rel != null) {
            int start = -1;
            final int length = rel.length();
            for (int c, i=0; i < length; i += Character.charCount(c)) {
                c = rel.codePointAt(i);
                if (Character.isDigit(c)) {
                    if (start < 0) start = i;
                } else if (c != '.' && start >= 0) {
                    return rel.substring(start, i);
                }
            }
        }
        return rel;
    }

    /**
     * Returns the version number of the {@literal Proj.4} library.
     *
     * @return the Proj.4 release string.
     *
     * @see #getVersion()
     * @see Proj4#version()
     */
    static native String getRelease();

    /**
     * Returns the namespace in which the code is valid.
     *
     * @see #getAuthority()
     * @see #getCode()
     */
    @Override
    public String getCodeSpace() {
        return Constants.PROJ4;
    }

    /**
     * Returns the {@literal Proj.4} definition string. This is the string given to the constructor,
     * expanded with as much information as possible.
     *
     * <div class="note"><b>Example:</b> "+proj=latlong +datum=WGS84 +ellps=WGS84 +towgs84=0,0,0"</div>
     *
     * @return the Proj.4 definition string.
     */
    @Override
    public native String getCode();

    /**
     * Returns the string representation of the PJ structure.
     *
     * @return the string representation, or {@code null} if none.
     */
    public InternationalString getDescription() {
        return null;
    }

    /**
     * Returns the Coordinate Reference System type.
     *
     * @return the CRS type.
     */
    public native Type getType();

    /**
     * The coordinate reference system (CRS) type returned by {@link PJ#getType()}.
     * In the Proj.4 library, a CRS can only be geographic, geocentric or projected,
     * without distinction between 2D and 3D CRS.
     */
    enum Type {
        /*
         * IMPLEMENTATION NOTE: Do not rename those fields, unless you update the
         * native C code accordingly.
         */

        /**
         * The CRS is of type {@link org.opengis.referencing.crs.GeographicCRS}.
         * The CRS can be two-dimensional or three-dimensional.
         */
        GEOGRAPHIC,

        /**
         * The CRS is of type {@link org.opengis.referencing.crs.GeocentricCRS}.
         * The CRS can only be three-dimensional.
         */
        GEOCENTRIC,

        /**
         * The CRS is of type {@link org.opengis.referencing.crs.ProjectedCRS}.
         * The CRS can be two-dimensional or three-dimensional.
         */
        PROJECTED
    }

    /**
     * Returns the semi-major axis length and the square of the ellipsoid eccentricity (ε²).
     * The eccentricity is related to axis length by ε=√(1-(<var>b</var>/<var>a</var>)²).
     * The eccentricity of a sphere is zero. Other related quantities are:
     *
     * <ul>
     *   <li>semi-minor axis length: b = a × √(1 - ε²)</li>
     *   <li>inverse flattening: invf = 1 / (1 - √(1 - ε²))</li>
     * </ul>
     *
     * @return the semi-major axis length and the eccentricity squared in an array of length 2.
     *
     * @see Ellipsoid#isSphere()
     * @see Ellipsoid#getInverseFlattening()
     */
    public native double[] getEllipsoidDefinition();

    /**
     * Transforms in-place the coordinates in the given array.
     * The coordinates array shall contain (<var>x</var>,<var>y</var>,<var>z</var>,…) tuples,
     * where the <var>z</var> and any additional dimensions are optional.
     * Note that any dimension after the <var>z</var> value are ignored.
     *
     * <p>Input and output units:</p>
     * <ul>
     *   <li>Angular units (as in longitude and latitudes) are decimal degrees.</li>
     *   <li>Linear units are usually metres, but this is actually projection-dependent.</li>
     * </ul>
     *
     * @param  target       the target CRS.
     * @param  dimension    the dimension of each coordinate value. Typically 2, 3 or 4.
     * @param  coordinates  the coordinates to transform, as a sequence of (<var>x</var>,<var>y</var>,&lt;<var>z</var>&gt;,…) tuples.
     * @param  offset       offset of the first coordinate in the given array.
     * @param  numPts       number of points to transform.
     * @throws NullPointerException if the {@code target} or {@code coordinates} argument is null.
     * @throws IndexOutOfBoundsException if the {@code offset} or {@code numPts} arguments are invalid.
     * @throws TransformException if the operation failed for another reason (provided by Proj.4).
     *
     * @see org.opengis.referencing.operation.MathTransform#transform(double[], int, double[], int, int)
     */
    public native void transform(PJ target, int dimension, double[] coordinates, int offset, int numPts)
            throws TransformException;

    /**
     * Returns a description of the last error that occurred, or {@code null} if none.
     *
     * @return the last error that occurred, or {@code null}.
     *
     * @todo this method is not thread-safe. Proj.4 provides a better alternative using a context parameter.
     *       Note that this method needs to be safe even if {@link #ptr} is 0.
     */
    native String getLastError();

    /**
     * Returns a hash code value for this {@literal Proj.4} object.
     */
    @Override
    public int hashCode() {
        return ~getCode().hashCode();
    }

    /**
     * Compares this identifier with the given object for equality.
     */
    @Override
    public boolean equals(final Object other) {
        return (other instanceof PJ) && getCode().equals(((PJ) other).getCode());
    }

    /**
     * Returns the string representation of the PJ structure.
     *
     * @return the string representation.
     */
    @Override
    public String toString() {
        return IdentifiedObjects.toString(this);
    }

    /**
     * Deallocates the native PJ data structure.
     * It is okay if this method is invoked more than once.
     */
    @Override
    @SuppressWarnings("FinalizeDeclaration")
    protected final native void finalize();

    /**
     * The object serialized in place of {@literal Proj.4} wrappers.
     * Deserialization checks for the presence of Proj.4 native library.
     *
     * @author  Martin Desruisseaux (Geomatys)
     * @version 0.8
     * @since   0.8
     * @module
     */
    private static final class Serialized implements Serializable {
        /**
         * For cross-version compatibility.
         */
        private static final long serialVersionUID = -5705027681492462823L;

        /**
         * The {@literal Proj.4} definition string.
         */
        private final String definition;

        /**
         * Creates a new proxy for the given {@literal Proj.4} definition.
         */
        Serialized(final String definition) {
            this.definition = definition;
        }

        /**
         * Automatically invoked on deserialization for reconstructing the wrapper
         * from the {@literal Proj.4} definition string.
         */
        protected final Object readResolve() throws ObjectStreamException {
            try {
                return new PJ(definition);
            } catch (UnsatisfiedLinkError | NoClassDefFoundError | InvalidGeodeticParameterException e) {
                throw (InvalidObjectException) new InvalidObjectException(Proj4.unavailable(e)).initCause(e);
            }
        }
    }

    /**
     * Invoked automatically on serialization.
     * Replaces this {@literal Proj.4} wrapper by a proxy without native resource.
     */
    protected final Object writeReplace() throws ObjectStreamException {
        return new Serialized(getCode());
    }
}
