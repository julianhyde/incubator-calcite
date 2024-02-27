/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.test;

/**
 * Can run SQL against different databases.
 *
 * @see Emulators
 */
interface Emulator {
  /** Starts all containers. */
  void start();

  /** Stops all containers. */
  void stop();

  /** Executes a SQL expression. */
  String execute(Type dbType, String exp);

  /**
   * Type identifier for the database used.
   */
  enum Type {
    MYSQL,
    ORACLE {
      @Override String query(final String sqlExpression) {
        return "SELECT " + sqlExpression + " FROM DUAL";
      }
    },
    POSTGRES_9_6,
    POSTGRES_12_2;

    /** Converts a SQL expression into a query. */
    String query(String sqlExpression) {
      return "SELECT " + sqlExpression;
    }
  }
}
