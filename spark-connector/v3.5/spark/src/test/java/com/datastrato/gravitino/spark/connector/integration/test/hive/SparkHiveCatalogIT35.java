/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.datastrato.gravitino.spark.connector.integration.test.hive;

import com.datastrato.gravitino.spark.connector.hive.GravitinoHiveCatalogSpark35;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class SparkHiveCatalogIT35 extends SparkHiveCatalogIT {
  @Test
  void testCatalogClassName() {
    String catalogClass =
        getSparkSession()
            .sessionState()
            .conf()
            .getConfString("spark.sql.catalog." + getCatalogName());
    Assertions.assertEquals(GravitinoHiveCatalogSpark35.class.getName(), catalogClass);
  }
}