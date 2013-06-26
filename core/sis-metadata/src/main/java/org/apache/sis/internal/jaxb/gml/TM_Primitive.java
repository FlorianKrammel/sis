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
package org.apache.sis.internal.jaxb.gml;

import java.util.Date;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import javax.xml.bind.annotation.XmlElement;
import org.apache.sis.internal.geoapi.temporal.Period;
import org.apache.sis.internal.geoapi.temporal.Instant;
import org.opengis.temporal.TemporalPrimitive;
import org.apache.sis.internal.jaxb.Context;
import org.apache.sis.internal.jaxb.XmlUtilities;
import org.apache.sis.internal.jaxb.gco.PropertyType;
import org.apache.sis.internal.util.TemporalUtilities;
import org.apache.sis.util.resources.Errors;


/**
 * JAXB adapter for {@link TemporalPrimitive}, in order to integrate the value in an element complying
 * with OGC/ISO standard. Note that the CRS is formatted using the GML schema, not the ISO 19139 one.
 *
 * @author  Guilhem Legal (Geomatys)
 * @author  Martin Desruisseaux (Geomatys)
 * @since   0.3 (derived from geotk-3.00)
 * @version 0.3
 * @module
 */
public final class TM_Primitive extends PropertyType<TM_Primitive, TemporalPrimitive> {
    /**
     * Empty constructor for JAXB.
     */
    public TM_Primitive() {
    }

    /**
     * Wraps a Temporal Primitive value at marshalling-time.
     *
     * @param metadata The metadata value to marshal.
     */
    private TM_Primitive(final TemporalPrimitive metadata) {
        super(metadata);
    }

    /**
     * Returns the Vertical CRS value wrapped by a temporal primitive element.
     *
     * @param  value The value to marshal.
     * @return The adapter which wraps the metadata value.
     */
    @Override
    protected TM_Primitive wrap(final TemporalPrimitive value) {
        return new TM_Primitive(value);
    }

    /**
     * Returns the GeoAPI interface which is bound by this adapter.
     */
    @Override
    protected Class<TemporalPrimitive> getBoundType() {
        return TemporalPrimitive.class;
    }

    /**
     * Returns the {@code TimePeriod} generated from the metadata value.
     * This method is systematically called at marshalling-time by JAXB.
     *
     * @return The time period, or {@code null}.
     */
    @XmlElement(name = "TimePeriod")
    public TimePeriod getTimePeriod() {
        if (!skip()) {
            final TemporalPrimitive metadata = this.metadata;
            if (metadata instanceof Period) {
                return new TimePeriod((Period) metadata);
            }
        }
        return null;
    }

    /**
     * Returns the {@link TimeInstant} generated from the metadata value.
     * This method is systematically called at marshalling-time by JAXB.
     *
     * @return The time instant, or {@code null}.
     */
    @XmlElement(name = "TimeInstant")
    public TimeInstant getTimeInstant() {
        if (!skip()) {
            final TemporalPrimitive metadata = this.metadata;
            if (metadata instanceof Instant) {
                return new TimeInstant((Instant) metadata);
            }
        }
        return null;
    }

    /**
     * Sets the value from the {@link TimePeriod}.
     * This method is called at unmarshalling-time by JAXB.
     *
     * @param period The adapter to set.
     */
    public void setTimePeriod(final TimePeriod period) {
        metadata = null; // Cleaned first in case of failure.
        if (period != null) {
            final Date begin = toDate(period.begin);
            final Date end   = toDate(period.end);
            if (begin != null || end != null) {
                if (begin != null && end != null && end.before(begin)) {
                    /*
                     * Be tolerant - we can treat such case as an empty range, which is a similar
                     * approach to what JDK does for Rectangle width and height. We will log with
                     * TemporalPrimitive as the source class, since it is the closest we can get
                     * to a public API.
                     */
                    final Context context = Context.current();
                    final LogRecord record = Errors.getResources(context != null ? context.getLocale() : null)
                            .getLogRecord(Level.WARNING, Errors.Keys.IllegalRange_2, begin, end);
                    record.setSourceClassName(TemporalPrimitive.class.getName());
                    record.setSourceMethodName("setTimePeriod");
                    Context.warningOccured(context, this, record);
                } else try {
                    metadata = TemporalUtilities.createPeriod(begin, end);
                    period.copyIdTo(metadata);
                } catch (UnsupportedOperationException e) {
                    warningOccured("setTimePeriod", e);
                }
            }
        }
    }

    /**
     * Sets the value from the {@link TimeInstant}.
     * This method is called at unmarshalling-time by JAXB.
     *
     * @param instant The adapter to set.
     */
    public void setTimeInstant(final TimeInstant instant) {
        metadata = null; // Cleaned first in case of failure.
        if (instant != null) {
            final Date position = XmlUtilities.toDate(instant.timePosition);
            if (position != null) try {
                metadata = TemporalUtilities.createInstant(position);
                instant.copyIdTo(metadata);
            } catch (UnsupportedOperationException e) {
                warningOccured("setTimeInstant", e);
            }
        }
    }

    /**
     * Returns the date of the given bounds, or {@code null} if none.
     */
    private static Date toDate(final TimePeriodBound bound) {
        return (bound != null) ? XmlUtilities.toDate(bound.calendar()) : null;
    }

    /**
     * Reports a warning for the given exception.
     *
     * @param method The name of the method to declare in the log record.
     * @param e the exception.
     */
    private void warningOccured(final String method, final Exception e) {
        Context.warningOccured(Context.current(), this, TM_Primitive.class, method, e, true);
    }
}
