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
package org.apache.sis.filter;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.apache.sis.feature.builder.AttributeRole;
import org.apache.sis.feature.builder.FeatureTypeBuilder;
import org.apache.sis.test.TestCase;
import org.junit.Test;
import org.opengis.feature.Feature;
import org.opengis.feature.FeatureType;
import org.opengis.filter.FilterFactory2;
import org.opengis.filter.identity.Identifier;

import static org.apache.sis.test.Assert.*;


/**
 * Tests {@link FilterByIdentifier}.
 *
 * @author  Johann Sorel (Geomatys)
 * @version 1.0
 * @since   1.0
 * @module
 */
public final strictfp class FilterByIdentifierTest extends TestCase {
    /**
     * The factory to use for creating the objects to test.
     */
    private final FilterFactory2 factory = new DefaultFilterFactory();

    /**
     * Test factory.
     */
    @Test
    public void testConstructor() {
        assertNotNull(factory.id(Collections.singleton(factory.featureId("abc"))));
    }

    /**
     * Tests evaluation.
     */
    @Test
    public void testEvaluate() {
        final FeatureTypeBuilder ftb = new FeatureTypeBuilder();
        ftb.setName("type");
        ftb.addAttribute(String.class).setName("att").addRole(AttributeRole.IDENTIFIER_COMPONENT);
        final FeatureType type = ftb.build();

        final Feature feature1 = type.newInstance();
        feature1.setPropertyValue("att", "123");

        final Feature feature2 = type.newInstance();
        feature2.setPropertyValue("att", "abc");

        final Feature feature3 = type.newInstance();
        feature3.setPropertyValue("att", "abc123");

        final Set<Identifier> ids = new HashSet<>();
        ids.add(factory.featureId("abc"));
        ids.add(factory.featureId("123"));
        final FilterByIdentifier id = new FilterByIdentifier(ids);

        assertTrue (id.evaluate(feature1));
        assertTrue (id.evaluate(feature2));
        assertFalse(id.evaluate(feature3));
    }

    /**
     * Tests serialization.
     */
    @Test
    public void testSerialize() {
        assertSerializedEquals(new FilterByIdentifier(Collections.singleton(factory.featureId("abc"))));
    }
}
