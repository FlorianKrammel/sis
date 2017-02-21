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
package org.apache.sis.referencing.gazetteer;

import org.opengis.referencing.operation.TransformException;
import org.opengis.geometry.DirectPosition;
import org.apache.sis.geometry.DirectPosition2D;
import org.apache.sis.internal.referencing.provider.TransverseMercator;
import org.apache.sis.referencing.CommonCRS;
import org.apache.sis.test.DependsOnMethod;
import org.apache.sis.test.DependsOn;
import org.apache.sis.test.TestCase;
import org.junit.Test;

import static org.junit.Assert.*;


/**
 * Tests {@link MilitaryGridReferenceSystem}.
 *
 * @author  Martin Desruisseaux (Geomatys)
 * @since   0.8
 * @version 0.8
 * @module
 */
@DependsOn(org.apache.sis.referencing.operation.projection.TransverseMercatorTest.class)
public final strictfp class MilitaryGridReferenceSystemTest extends TestCase {
    /**
     * Returns a coder instance to test.
     */
    private MilitaryGridReferenceSystem.Coder coder() {
        return new MilitaryGridReferenceSystem().createCoder();
    }

    /**
     * Verifies relationship between static fields documented in {@link Encoder}.
     */
    @Test
    public void verifyInvariants() {
        assertEquals("GRID_SQUARE_SIZE",
             StrictMath.pow(10, MilitaryGridReferenceSystem.METRE_PRECISION_DIGITS),
                                MilitaryGridReferenceSystem.GRID_SQUARE_SIZE, STRICT);
    }

    /**
     * Tests {@link MilitaryGridReferenceSystem.Encoder#latitudeBand(double)}.
     */
    @Test
    public void testLatitudeBand() {
        assertEquals("80°S", 'C', MilitaryGridReferenceSystem.Encoder.latitudeBand(-80));
        assertEquals("45°N", 'T', MilitaryGridReferenceSystem.Encoder.latitudeBand( 45));
        assertEquals("55°N", 'U', MilitaryGridReferenceSystem.Encoder.latitudeBand( 55));
        assertEquals("56°N", 'V', MilitaryGridReferenceSystem.Encoder.latitudeBand( 56));
        assertEquals("63°N", 'V', MilitaryGridReferenceSystem.Encoder.latitudeBand( 63));
        assertEquals("64°N", 'W', MilitaryGridReferenceSystem.Encoder.latitudeBand( 64));
        assertEquals("71°N", 'W', MilitaryGridReferenceSystem.Encoder.latitudeBand( 71));
        assertEquals("72°N", 'X', MilitaryGridReferenceSystem.Encoder.latitudeBand( 72));
        assertEquals("84°N", 'X', MilitaryGridReferenceSystem.Encoder.latitudeBand( 84));
    }

    /**
     * Verifies that {@link Zoner#isNorway(double)} and {@link Zoner#isSvalbard(double)}
     * are consistent with the latitude bands.
     */
    @Test
    public void verifyZonerConsistency() {
        for (double φ = TransverseMercator.Zoner.SOUTH_BOUNDS; φ < TransverseMercator.Zoner.NORTH_BOUNDS; φ++) {
            final String latitude = String.valueOf(φ);
            final char band = MilitaryGridReferenceSystem.Encoder.latitudeBand(φ);
            assertTrue  (latitude, band >= 'C' && band <= 'X');
            assertEquals(latitude, band == 'V', TransverseMercator.Zoner.isNorway(φ));
            assertEquals(latitude, band == 'X', TransverseMercator.Zoner.isSvalbard(φ));
        }
    }

    /**
     * Tests encoding of various coordinates.
     *
     * @throws TransformException if an error occurred while computing the MGRS label.
     */
    @Test
    @DependsOnMethod({"verifyInvariants", "testLatitudeBand"})
    public void testEncoding() throws TransformException {
        final MilitaryGridReferenceSystem.Coder coder = coder();
        final DirectPosition2D position = new DirectPosition2D();
        /*
         * 41°N 10°E (UTM zone 32)
         */
        position.setCoordinateReferenceSystem(CommonCRS.WGS84.universal(41, 10));
        position.x =  584102;
        position.y = 4539239;
        assertEquals("32TNL8410239239", coder.encode(position));
        /*
         * 82°N 10°W (UTM zone 29) — should instantiate a new MilitaryGridReferenceSystem.Encoder.
         */
        position.setCoordinateReferenceSystem(CommonCRS.WGS84.universal(82, -10));
        position.x =  484463;
        position.y = 9104963;
        assertEquals("29XMM8446304963", coder.encode(position));
        /*
         * 41°S 10°E (UTM zone 32) — should reuse the Encoder created in first test.
         */
        position.setCoordinateReferenceSystem(CommonCRS.WGS84.universal(-41, 10));
        position.x =  584102;
        position.y = 5460761;
        assertEquals("32GNV8410260761", coder.encode(position));
        /*
         * 82°N 10°E (UTM zone 32) — in this special case, zone 32 is replaced by zone 33.
         * Call to WGS84.UTM(φ,λ) needs to specify a smaller latitude for getting zone 32.
         */
        position.setCoordinateReferenceSystem(CommonCRS.WGS84.universal(40, 10));
        position.x =  515537;
        position.y = 9104963;
        assertEquals("33XVM2240708183", coder.encode(position));
        /*
         * Same position as previously tested, but using geographic coordinates.
         */
        position.setCoordinateReferenceSystem(CommonCRS.WGS84.geographic());
        position.x = 82;
        position.y = 10;
        assertEquals("33XVM2240608183", coder.encode(position));
        position.x = -41;
        position.y = 10;
        assertEquals("32GNV8410260761", coder.encode(position));
    }

    /**
     * Tests decoding of various coordinates, all at the same resolution.
     *
     * @throws TransformException if an error occurred while computing the coordinate.
     */
    @Test
    @DependsOnMethod("verifyInvariants")
    public void testDecoding() throws TransformException {
        final MilitaryGridReferenceSystem.Coder coder = coder();
        DirectPosition position;

        position = coder.decode("32TNL8410239239");
        assertSame("crs", CommonCRS.WGS84.universal(41, 10), position.getCoordinateReferenceSystem());
        assertEquals("Easting",   584102, position.getOrdinate(0), STRICT);
        assertEquals("Northing", 4539239, position.getOrdinate(1), STRICT);

        position = coder.decode("29XMM8446304963");
        assertSame("crs", CommonCRS.WGS84.universal(82, -10), position.getCoordinateReferenceSystem());
        assertEquals("Easting",   484463, position.getOrdinate(0), STRICT);
        assertEquals("Northing", 9104963, position.getOrdinate(1), STRICT);

        position = coder.decode("32GNV8410260761");
        assertSame("crs", CommonCRS.WGS84.universal(-41, 10), position.getCoordinateReferenceSystem());
        assertEquals("Easting",   584102, position.getOrdinate(0), STRICT);
        assertEquals("Northing", 5460761, position.getOrdinate(1), STRICT);

        position = coder.decode("33XVM2240708183");
        assertSame("crs", CommonCRS.WGS84.universal(82, 10), position.getCoordinateReferenceSystem());
//      assertEquals("Easting",   515537, position.getOrdinate(0), STRICT);
//      assertEquals("Northing", 9104963, position.getOrdinate(1), STRICT);
    }

    /**
     * Tests encoding of the same coordinate at various precision.
     *
     * @throws TransformException if an error occurred while computing the MGRS label.
     */
    @Test
    @DependsOnMethod("testEncoding")
    public void testPrecision() throws TransformException {
        final MilitaryGridReferenceSystem.Coder coder = coder();
        final DirectPosition2D position = new DirectPosition2D(CommonCRS.WGS84.universal(13, 103));
        position.x =  377299;
        position.y = 1483035;
        assertEquals("precision", 1, coder.getPrecision(), STRICT);
        assertEquals("48PUV7729983035", coder.encode(position));
        coder.setPrecision(10);
        assertEquals("precision", 10, coder.getPrecision(), STRICT);
        assertEquals("48PUV77298303", coder.encode(position));
        coder.setPrecision(304);
        assertEquals("precision", 100, coder.getPrecision(), STRICT);
        assertEquals("48PUV772830", coder.encode(position));
        coder.setPrecision(1002);
        assertEquals("precision", 1000, coder.getPrecision(), STRICT);
        assertEquals("48PUV7783", coder.encode(position));
        coder.setPrecision(10000);
        assertEquals("precision", 10000, coder.getPrecision(), STRICT);
        assertEquals("48PUV78", coder.encode(position));
        coder.setPrecision(990004);
        assertEquals("precision", 100000, coder.getPrecision(), STRICT);
        assertEquals("48PUV", coder.encode(position));
        coder.setPrecision(1000000);
        assertEquals("precision", 1000000, coder.getPrecision(), STRICT);
        assertEquals("48P", coder.encode(position));
    }

    /**
     * Tests encoding of the same coordinate with various separators, mixed with various precisions.
     *
     * @throws TransformException if an error occurred while computing the MGRS label.
     */
    @Test
    @DependsOnMethod("testPrecision")
    public void testSeparator() throws TransformException {
        final MilitaryGridReferenceSystem.Coder coder = coder();
        final DirectPosition2D position = new DirectPosition2D(CommonCRS.WGS84.universal(13, 103));
        position.x =  377299;
        position.y = 1483035;
        assertEquals("separator", "", coder.getSeparator());
        assertEquals("48PUV7729983035", coder.encode(position));

        coder.setSeparator(" ");
        assertEquals("separator", " ", coder.getSeparator());
        assertEquals("48 P UV 77299 83035", coder.encode(position));

        coder.setSeparator("/");
        coder.setPrecision(100000);
        assertEquals("separator", "/", coder.getSeparator());
        assertEquals("48/P/UV", coder.encode(position));
    }

    /**
     * Tests decoding the same coordinates with different separators at different resolutions.
     *
     * @throws TransformException if an error occurred while computing the coordinate.
     */
    @Test
    @DependsOnMethod("testDecoding")
    public void testDecodingVariants() throws TransformException {
        final MilitaryGridReferenceSystem.Coder coder = coder();
        coder.setSeparator(" / ");
        DirectPosition position;

        position = coder.decode("32TNL8410239239");
        assertEquals("32TNL8410239239", position, coder.decode("32/T/NL/84102/39239"));
        assertEquals("Easting",   584102, position.getOrdinate(0), STRICT);
        assertEquals("Northing", 4539239, position.getOrdinate(1), STRICT);

        position = coder.decode("32TNL8439");
        assertEquals("32TNL8439", position, coder.decode("32/T/NL/84/39"));
        assertEquals("Easting",   584000, position.getOrdinate(0), STRICT);
        assertEquals("Northing", 4539000, position.getOrdinate(1), STRICT);

        position = coder.decode("32TNL");
        assertEquals("32TNL", position, coder.decode("32/T/NL"));
        assertEquals("Easting",   500000, position.getOrdinate(0), STRICT);
        assertEquals("Northing", 4500000, position.getOrdinate(1), STRICT);

        position = coder.decode("32T");
        assertEquals("32T", position, coder.decode("32/T"));
        assertEquals("Easting",   100000, position.getOrdinate(0), STRICT);
//      assertEquals("Northing", 4000000, position.getOrdinate(1), STRICT);
    }
}
