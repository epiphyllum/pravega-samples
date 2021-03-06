/*
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 */
package io.pravega.connectors.nytaxi;

import io.pravega.client.stream.Stream;
import io.pravega.connectors.flink.FlinkPravegaJsonTableSource;
import io.pravega.connectors.nytaxi.common.TripRecord;
import lombok.extern.slf4j.Slf4j;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.TableSchema;
import org.apache.flink.table.api.java.StreamTableEnvironment;
import org.apache.flink.table.sources.tsextractors.ExistingField;
import org.apache.flink.table.sources.wmstrategies.BoundedOutOfOrderTimestamps;
import org.apache.flink.types.Row;

/**
 * Identify the popular destination based on total trips that exceeds a given threshold for specific time window.
 */

@Slf4j
public class PopularDestinationQuery extends AbstractHandler {

    public PopularDestinationQuery (String ... args) {
        super(args);
    }

    @Override
    public void handleRequest() {

        TableSchema tableSchema = TripRecord.getTableSchema();

        FlinkPravegaJsonTableSource source = FlinkPravegaJsonTableSource.builder()
                .forStream(Stream.of(getScope(), getStream()).getScopedName())
                .withPravegaConfig(getPravegaConfig())
                .failOnMissingField(true)
                .withRowtimeAttribute("pickupTime",
                        new ExistingField("pickupTime"),
                        new BoundedOutOfOrderTimestamps(30000L))
                .withSchema(tableSchema)
                .withReaderGroupScope(getScope())
                .build();


        StreamExecutionEnvironment env = getStreamExecutionEnvironment();

        // create a TableEnvironment
        StreamTableEnvironment tEnv = StreamTableEnvironment.create(env);
        tEnv.registerTableSource("TaxiRide", source);

        String query =
                "SELECT " +
                        "destLocationId, wstart, wend, cnt " +
                        "FROM " +
                        "(SELECT " +
                        "destLocationId, " +
                        "HOP_START(pickupTime, INTERVAL '5' MINUTE, INTERVAL '15' MINUTE) AS wstart, " +
                        "HOP_END(pickupTime, INTERVAL '5' MINUTE, INTERVAL '15' MINUTE) AS wend, " +
                        "COUNT(destLocationId) AS cnt " +
                        "FROM " +
                        "(SELECT " +
                        "pickupTime, " +
                        "destLocationId " +
                        "FROM TaxiRide) " +
                        "GROUP BY destLocationId, HOP(pickupTime, INTERVAL '5' MINUTE, INTERVAL '15' MINUTE)) " +
                        "WHERE cnt > " + getLimit();

        Table results = tEnv.sqlQuery(query);

        tEnv.toAppendStream(results, Row.class).print();

        try {
            env.execute("Popular-Destination");
        } catch (Exception e) {
            log.error("Application Failed", e);
        }
    }
}