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

import org.apache.calcite.DataContext;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.interpreter.Interpreter;
import org.apache.calcite.linq4j.QueryProvider;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.Planner;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;

/**
 * Unit tests for {@link org.apache.calcite.interpreter.Interpreter}.
 */
public class InterpreterTest {
  private SchemaPlus rootSchema;
  private Planner planner;
  private MyDataContext dataContext;

  /** Implementation of {@link DataContext} for executing queries without a
   * connection. */
  private class MyDataContext implements DataContext {
    private final Planner planner;

    MyDataContext(Planner planner) {
      this.planner = planner;
    }

    public SchemaPlus getRootSchema() {
      return rootSchema;
    }

    public JavaTypeFactory getTypeFactory() {
      return (JavaTypeFactory) planner.getTypeFactory();
    }

    public QueryProvider getQueryProvider() {
      return null;
    }

    public Object get(String name) {
      return null;
    }
  }

  @Before public void setUp() {
    rootSchema = Frameworks.createRootSchema(true);
    final FrameworkConfig config = Frameworks.newConfigBuilder()
        .parserConfig(SqlParser.Config.DEFAULT)
        .defaultSchema(
            CalciteAssert.addSchema(rootSchema, CalciteAssert.SchemaSpec.HR))
        .build();
    planner = Frameworks.getPlanner(config);
    dataContext = new MyDataContext(planner);
  }

  @After public void tearDown() {
    rootSchema = null;
    planner = null;
    dataContext = null;
  }

  /** Tests executing a simple plan using an interpreter. */
  @Test public void testInterpretProjectFilterValues() throws Exception {
    SqlNode parse =
        planner.parse("select y, x\n"
            + "from (values (1, 'a'), (2, 'b'), (3, 'c')) as t(x, y)\n"
            + "where x > 1");

    SqlNode validate = planner.validate(parse);
    RelNode convert = planner.rel(validate).rel;

    final Interpreter interpreter = new Interpreter(dataContext, convert);
    assertRows(interpreter, "[b, 2]", "[c, 3]");
  }

  /** Tests a plan where the sort field is projected away. */
  @Test public void testInterpretOrder() throws Exception {
    final String sql = "select y\n"
        + "from (values (1, 'a'), (2, 'b'), (3, 'c')) as t(x, y)\n"
        + "order by -x";
    SqlNode parse = planner.parse(sql);
    SqlNode validate = planner.validate(parse);
    RelNode convert = planner.rel(validate).project();

    final Interpreter interpreter = new Interpreter(dataContext, convert);
    assertRows(interpreter, "[c]", "[b]", "[a]");
  }

  @Test public void testInterpretMultiset() throws Exception {
    final String sql = "select multiset['a', 'b', 'c']";
    SqlNode parse = planner.parse(sql);
    SqlNode validate = planner.validate(parse);
    RelNode convert = planner.rel(validate).project();

    final Interpreter interpreter = new Interpreter(dataContext, convert);
    assertRows(interpreter, "[[a, b, c]]");
  }

  private static void assertRows(Interpreter interpreter, String... rows) {
    assertRows(interpreter, false, rows);
  }

  private static void assertRowsUnordered(Interpreter interpreter,
      String... rows) {
    assertRows(interpreter, true, rows);
  }

  private static void assertRows(Interpreter interpreter,
      boolean unordered, String... rows) {
    final List<String> list = new ArrayList<>();
    for (Object[] row : interpreter) {
      list.add(Arrays.toString(row));
    }
    final List<String> expected = Arrays.asList(rows);
    if (unordered) {
      Collections.sort(list);
      Collections.sort(expected);
    }
    assertThat(list, equalTo(expected));
  }

  /**
   * Interprets the sql and checks result with specified rows.
   */
  private void interpretSqlAndCheck(String sql,
      boolean unordered, String... rows) throws Exception {
    SqlNode parse = planner.parse(sql);
    SqlNode validate = planner.validate(parse);
    RelNode convert = planner.rel(validate).rel;
    final Interpreter interpreter = new Interpreter(dataContext, convert);
    assertRows(interpreter, unordered, rows);
  }

  private void interpretSqlAndCheck(String sql, String... rows) throws Exception {
    interpretSqlAndCheck(sql, false, rows);
  }

  /** Tests executing a simple plan using an interpreter. */
  @Test public void testInterpretTable() throws Exception {
    interpretSqlAndCheck("select * from \"hr\".\"emps\" order by \"empid\"",
        "[100, 10, Bill, 10000.0, 1000]",
        "[110, 10, Theodore, 11500.0, 250]",
        "[150, 10, Sebastian, 7000.0, null]",
        "[200, 20, Eric, 8000.0, 500]");
  }

  /** Tests executing a plan on a
   * {@link org.apache.calcite.schema.ScannableTable} using an interpreter. */
  @Test public void testInterpretScannableTable() throws Exception {
    rootSchema.add("beatles", new ScannableTableTest.BeatlesTable());
    interpretSqlAndCheck("select * from \"beatles\" order by \"i\"",
        "[4, John]",
        "[4, Paul]",
        "[5, Ringo]",
        "[6, George]");
  }

  @Test public void testAggregateCount() throws Exception {
    rootSchema.add("beatles", new ScannableTableTest.BeatlesTable());
    interpretSqlAndCheck("select count(*) from \"beatles\"", "[4]");
  }

  @Test public void testAggregateMax() throws Exception {
    rootSchema.add("beatles", new ScannableTableTest.BeatlesTable());
    interpretSqlAndCheck("select max(\"i\") from \"beatles\"", "[6]");
  }

  @Test public void testAggregateMin() throws Exception {
    rootSchema.add("beatles", new ScannableTableTest.BeatlesTable());
    interpretSqlAndCheck("select min(\"i\") from \"beatles\"", "[4]");
  }

  @Test public void testAggregateGroup() throws Exception {
    rootSchema.add("beatles", new ScannableTableTest.BeatlesTable());
    interpretSqlAndCheck("select \"j\", count(*) from \"beatles\" group by \"j\"",
        true,
        "[George, 1]",
        "[Paul, 1]",
        "[John, 1]",
        "[Ringo, 1]");
  }

  @Test public void testAggregateGroupFilter() throws Exception {
    rootSchema.add("beatles", new ScannableTableTest.BeatlesTable());
    final String sql = "select \"j\",\n"
        + "  count(*) filter (where char_length(\"j\") > 4)\n"
        + "from \"beatles\" group by \"j\"";
    interpretSqlAndCheck(sql, true,
        "[George, 1]",
        "[Paul, 0]",
        "[John, 0]",
        "[Ringo, 1]");
  }

  /** Tests executing a plan on a single-column
   * {@link org.apache.calcite.schema.ScannableTable} using an interpreter. */
  @Test public void testInterpretSimpleScannableTable() throws Exception {
    rootSchema.add("simple", new ScannableTableTest.SimpleTable());
    interpretSqlAndCheck("select * from \"simple\" limit 2", "[0]", "[10]");
  }

  /** Tests executing a UNION ALL query using an interpreter. */
  @Test public void testInterpretUnionAll() throws Exception {
    rootSchema.add("simple", new ScannableTableTest.SimpleTable());
    final String sql = "select * from \"simple\"\n"
        + "union all\n"
        + "select * from \"simple\"";
    interpretSqlAndCheck(sql, true,
        "[0]", "[10]", "[20]", "[30]", "[0]", "[10]", "[20]", "[30]");
  }

  /** Tests executing a UNION query using an interpreter. */
  @Test public void testInterpretUnion() throws Exception {
    rootSchema.add("simple", new ScannableTableTest.SimpleTable());
    final String sql = "select * from \"simple\"\n"
        + "union\n"
        + "select * from \"simple\"";
    interpretSqlAndCheck(sql, true, "[0]", "[10]", "[20]", "[30]");
  }

  @Test public void testInterpretUnionWithNullValue() throws Exception {
    final String sql = "select * from\n"
        + "(select x, y from (values (cast(NULL as int), cast(NULL as varchar(1))),\n"
        + "(cast(NULL as int), cast(NULL as varchar(1)))) as t(x, y))\n"
        + "union\n"
        + "(select x, y from (values (cast(NULL as int), cast(NULL as varchar(1)))) as t2(x, y))";
    interpretSqlAndCheck(sql, "[null, null]");
  }

  @Test public void testInterpretUnionAllWithNullValue() throws Exception {
    final String sql = "select * from\n"
        + "(select x, y from (values (cast(NULL as int), cast(NULL as varchar(1))),\n"
        + "(cast(NULL as int), cast(NULL as varchar(1)))) as t(x, y))\n"
        + "union all\n"
        + "(select x, y from (values (cast(NULL as int), cast(NULL as varchar(1)))) as t2(x, y))";
    interpretSqlAndCheck(sql, "[null, null]", "[null, null]", "[null, null]");
  }

  @Test public void testInterpretIntersect() throws Exception {
    final String sql = "select * from\n"
        + "(select x, y from (values (1, 'a'), (1, 'a'), (2, 'b'), (3, 'c')) as t(x, y))\n"
        + "intersect\n"
        + "(select x, y from (values (1, 'a'), (2, 'c'), (4, 'x')) as t2(x, y))";
    interpretSqlAndCheck(sql, "[1, a]");
  }

  @Test public void testInterpretIntersectAll() throws Exception {
    final String sql = "select * from\n"
        + "(select x, y from (values (1, 'a'), (1, 'a'), (2, 'b'), (3, 'c')) as t(x, y))\n"
        + "intersect all\n"
        + "(select x, y from (values (1, 'a'), (2, 'c'), (4, 'x')) as t2(x, y))";
    interpretSqlAndCheck(sql, "[1, a]");
  }

  @Test public void testInterpretIntersectWithNullValue() throws Exception {
    final String sql = "select * from\n"
        + "(select x, y from (values (cast(NULL as int), cast(NULL as varchar(1))),\n"
        + " (cast(NULL as int), cast(NULL as varchar(1)))) as t(x, y))\n"
        + "intersect\n"
        + "(select x, y from (values (cast(NULL as int), cast(NULL as varchar(1)))) as t2(x, y))";
    interpretSqlAndCheck(sql, "[null, null]");
  }

  @Test public void testInterpretIntersectAllWithNullValue() throws Exception {
    final String sql = "select * from\n"
        + "(select x, y from (values (cast(NULL as int), cast(NULL as varchar(1))),\n"
        + " (cast(NULL as int), cast(NULL as varchar(1)))) as t(x, y))\n"
        + "intersect all\n"
        + "(select x, y from (values (cast(NULL as int), cast(NULL as varchar(1)))) as t2(x, y))";
    interpretSqlAndCheck(sql, "[null, null]");
  }

  @Test public void testInterpretMinus() throws Exception {
    final String sql = "select * from\n"
        + "(select x, y from (values (1, 'a'), (2, 'b'), (2, 'b'), (3, 'c')) as t(x, y))\n"
        + "except\n"
        + "(select x, y from (values (1, 'a'), (2, 'c'), (4, 'x')) as t2(x, y))";
    interpretSqlAndCheck(sql, "[2, b]", "[3, c]");
  }

  @Test public void testDuplicateRowInterpretMinus() throws Exception {
    final String sql = "select * from\n"
        + "(select x, y from (values (2, 'b'), (2, 'b')) as t(x, y))\n"
        + "except\n"
        + "(select x, y from (values (2, 'b')) as t2(x, y))";
    interpretSqlAndCheck(sql, new String[0]);
  }

  @Test public void testInterpretMinusAll() throws Exception {
    final String sql = "select * from\n"
        + "(select x, y from (values (1, 'a'), (2, 'b'), (2, 'b'), (3, 'c')) as t(x, y))\n"
        + "except all\n"
        + "(select x, y from (values (1, 'a'), (2, 'c'), (4, 'x')) as t2(x, y))";
    interpretSqlAndCheck(sql, "[2, b]", "[2, b]", "[3, c]");
  }

  @Test public void testDuplicateRowInterpretMinusAll() throws Exception {
    final String sql = "select * from\n"
        + "(select x, y from (values (2, 'b'), (2, 'b')) as t(x, y))\n"
        + "except all\n"
        + "(select x, y from (values (2, 'b')) as t2(x, y))\n";
    interpretSqlAndCheck(sql, "[2, b]");
  }

  @Test public void testInterpretMinusAllWithNullValue() throws Exception {
    final String sql = "select * from\n"
        + "(select x, y from (values (cast(NULL as int), cast(NULL as varchar(1))),\n"
        + " (cast(NULL as int), cast(NULL as varchar(1)))) as t(x, y))\n"
        + "except all\n"
        + "(select x, y from (values (cast(NULL as int), cast(NULL as varchar(1)))) as t2(x, y))\n";
    interpretSqlAndCheck(sql, "[null, null]");
  }

  @Test public void testInterpretMinusWithNullValue() throws Exception {
    final String sql = "select * from\n"
        + "(select x, y from (values (cast(NULL as int), cast(NULL as varchar(1))),\n"
        + "(cast(NULL as int), cast(NULL as varchar(1)))) as t(x, y))\n"
        + "except\n"
        + "(select x, y from (values (cast(NULL as int), cast(NULL as varchar(1)))) as t2(x, y))\n";
    interpretSqlAndCheck(sql, new String[0]);
  }
}

// End InterpreterTest.java
