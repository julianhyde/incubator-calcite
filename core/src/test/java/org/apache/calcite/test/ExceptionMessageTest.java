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

import org.apache.calcite.adapter.java.ReflectiveSchema;
import org.apache.calcite.jdbc.CalciteConnection;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.tools.RelRunner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Test cases to check that necessary information from underlying exceptions
 * is correctly propagated via {@link SQLException}s.
 */
public class ExceptionMessageTest {
  private Connection conn;
  private RelBuilder builder;

  /**
   * Simple reflective schema that provides valid and invalid entries.
   */
  @SuppressWarnings("UnusedDeclaration")
  public static class TestSchema {
    public Entry[] entries = {
        new Entry(1, "name1"),
        new Entry(2, "name2")
    };

    public Iterable<Entry> badEntries = () -> {
      throw new IllegalStateException("Can't iterate over badEntries");
    };
  }

  /**
   * Entries made available in the reflective TestSchema.
   */
  public static class Entry {
    public int id;
    public String name;

    public Entry(int id, String name) {
      this.id = id;
      this.name = name;
    }
  }

  @BeforeEach
  public void setUp() throws SQLException {
    Connection connection = DriverManager.getConnection("jdbc:calcite:");
    CalciteConnection calciteConnection =
        connection.unwrap(CalciteConnection.class);
    SchemaPlus rootSchema = calciteConnection.getRootSchema();
    rootSchema.add("test", new ReflectiveSchema(new TestSchema()));
    calciteConnection.setSchema("test");
    FrameworkConfig config = Frameworks.newConfigBuilder()
        .defaultSchema(rootSchema)
        .build();
    this.conn = calciteConnection;
    this.builder = RelBuilder.create(config);
  }

  private void runQuery(String sql) throws SQLException {
    Statement stmt = conn.createStatement();
    try {
      stmt.executeQuery(sql);
    } finally {
      try {
        stmt.close();
      } catch (Exception e) {
        // We catch a possible exception on close so that we know we're not
        // masking the query exception with the close exception
        fail("Error on close");
      }
    }
  }

  private void runQuery(RelNode relNode) throws SQLException {
    RelRunner relRunner = conn.unwrap(RelRunner.class);
    PreparedStatement preparedStatement = null;
    try {
      preparedStatement = relRunner.prepare(relNode);
      preparedStatement.executeQuery();
    } finally {
      try {
        if (null != preparedStatement) {
          preparedStatement.close();
        }
      } catch (Exception e) {
        // We catch a possible exception on close so that we know we're not
        // masking the query exception with the close exception
        fail("Error on close");
      }
    }
  }

  @Test
  void testValidQuery() throws SQLException {
    // Just ensure that we're actually dealing with a valid connection
    // to be sure that the results of the other tests can be trusted
    runQuery("select * from \"entries\"");
  }

  @Test void testNonSqlException() throws SQLException {
    try {
      runQuery("select * from \"badEntries\"");
      fail("Query badEntries should result in an exception");
    } catch (SQLException e) {
      assertThat(e.getMessage(),
          equalTo("Error while executing SQL \"select * from \"badEntries\"\": "
              + "Can't iterate over badEntries"));
    }
  }

  @Test void testSyntaxError() {
    try {
      runQuery("invalid sql");
      fail("Query should fail");
    } catch (SQLException e) {
      assertThat(e.getMessage(),
          equalTo("Error while executing SQL \"invalid sql\": parse failed: "
              + "Non-query expression encountered in illegal context"));
    }
  }

  @Test void testSemanticError() {
    try {
      // implicit type coercion.
      runQuery("select \"name\" - \"id\" from \"entries\"");
    } catch (SQLException e) {
      assertThat(e.getMessage(),
          containsString("Cannot apply '-' to arguments"));
    }
  }

  @Test void testNonexistentTable() {
    try {
      runQuery("select name from \"nonexistentTable\"");
      fail("Query should fail");
    } catch (SQLException e) {
      assertThat(e.getMessage(),
          containsString("Object 'nonexistentTable' not found"));
    }
  }

  @Test
  void testValidRelNodeQuery() throws SQLException {
    final RelNode relNode = builder
        .scan("test","entries")
        .project(builder.field("name"))
        .build();
    runQuery(relNode);
  }

  @Test
  void testRelNodeQueryException() throws SQLException {
    try {
      final RelNode relNode = builder
          .scan("test","entries")
          .project(builder.call(SqlStdOperatorTable.ABS,builder.field("name")))
          .build();
      runQuery(relNode);
      fail("Query badEntries should result in an exception");
    } catch (RuntimeException e) {
      assertThat(e.getMessage(),
          equalTo("java.sql.SQLException: Error while preparing statement [\n" +
              "LogicalProject($f0=[ABS($1)])\n" +
              "  LogicalTableScan(table=[[test, entries]])\n" +
              "]"));
    }
  }
}
