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
package org.apache.calcite.sql.test;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.parser.StringAndPos;
import org.apache.calcite.sql.validate.SqlValidator;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.hamcrest.Matcher;

import java.util.List;
import java.util.function.UnaryOperator;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import static java.util.Objects.requireNonNull;

/**
 * Implementation of {@link SqlFixture}.
 */
class SqlFixtureImpl implements SqlFixture {
  public static final SqlFixtureImpl DEFAULT =
      new SqlFixtureImpl(SqlNewTestFactory.INSTANCE,
          SqlValidatorTester.DEFAULT, false);

  private final SqlNewTestFactory factory;
  private final SqlTester tester;
  private final boolean brokenTestsEnabled;

  SqlFixtureImpl(SqlNewTestFactory factory, SqlTester tester,
      boolean brokenTestsEnabled) {
    this.factory = requireNonNull(factory, "factory");
    this.tester = requireNonNull(tester, "tester");
    this.brokenTestsEnabled = brokenTestsEnabled;
  }

  @Override public void close() {
  }

  @Override public SqlNewTestFactory getFactory() {
    return factory;
  }

  @Override public SqlTester getTester() {
    return tester;
  }

  @Override public SqlFixtureImpl withFactory(
      UnaryOperator<SqlNewTestFactory> transform) {
    final SqlNewTestFactory factory = transform.apply(this.factory);
    if (factory == this.factory) {
      return this;
    }
    return new SqlFixtureImpl(factory, tester, brokenTestsEnabled);
  }

  @Override public SqlFixture withTester(UnaryOperator<SqlTester> transform) {
    final SqlTester tester = transform.apply(this.tester);
    if (tester == this.tester) {
      return this;
    }
    return new SqlFixtureImpl(factory, tester, brokenTestsEnabled);
  }

  @Override public boolean brokenTestsEnabled() {
    return brokenTestsEnabled;
  }

  @Override public SqlFixture withBrokenTestsEnabled(boolean enable) {
    return enable == this.brokenTestsEnabled ? this
        : new SqlFixtureImpl(factory, tester, enable);
  }

  @Override public SqlFixture setFor(SqlOperator operator,
      VmName... unimplementedVmNames) {
    return this;
  }

  SqlNode parseAndValidate(SqlValidator validator, String sql) {
    SqlNode sqlNode;
    try {
      sqlNode = tester.parseQuery(factory, sql);
    } catch (Throwable e) {
      throw new RuntimeException("Error while parsing query: " + sql, e);
    }
    return validator.validate(sqlNode);
  }

  @Override public void checkColumnType(String sql, String expected) {
    tester.validateAndThen(factory, StringAndPos.of(sql),
        checkColumnTypeAction(is(expected)));
  }

  @Override public void checkType(String expression, String type) {
    forEachQueryValidateAndThen(StringAndPos.of(expression),
        checkColumnTypeAction(is(type)));
  }

  private static SqlTester.ValidatedNodeConsumer checkColumnTypeAction(
      Matcher<String> matcher) {
    return (sql, validator, validatedNode) -> {
      final RelDataType rowType =
          validator.getValidatedNodeType(validatedNode);
      final List<RelDataTypeField> fields = rowType.getFieldList();
      assertEquals(1, fields.size(), "expected query to return 1 field");
      final RelDataType actualType = fields.get(0).getType();
      String actual = SqlTests.getTypeString(actualType);
      assertThat(actual, matcher);
    };
  }

  @Override public void checkQuery(String sql) {
    tester.assertExceptionIsThrown(factory, StringAndPos.of(sql), null);
  }

  void forEachQueryValidateAndThen(StringAndPos expression,
      SqlTester.ValidatedNodeConsumer consumer) {
    tester.forEachQuery(factory, expression.addCarets(), query ->
        tester.validateAndThen(factory, StringAndPos.of(query), consumer));
  }

  @Override public void checkFails(StringAndPos sap, String expectedError,
      boolean runtime) {
    final String sql = "values (" + sap.addCarets() + ")";
    if (runtime) {
      // We need to test that the expression fails at runtime.
      // Ironically, that means that it must succeed at prepare time.
      SqlValidator validator = factory.createValidator();
      SqlNode n = parseAndValidate(validator, sql);
      assertNotNull(n);
    } else {
      checkQueryFails(StringAndPos.of(sql),
          expectedError);
    }
  }

  @Override public void checkQueryFails(StringAndPos sap,
      String expectedError) {
    tester.assertExceptionIsThrown(factory, sap, expectedError);
  }

  @Override public void checkAggFails(
      String expr,
      String[] inputValues,
      String expectedError,
      boolean runtime) {
    final String sql =
        SqlTests.generateAggQuery(expr, inputValues);
    if (runtime) {
      SqlValidator validator = factory.createValidator();
      SqlNode n = parseAndValidate(validator, sql);
      assertNotNull(n);
    } else {
      checkQueryFails(StringAndPos.of(sql), expectedError);
    }
  }

  @Override public void checkAgg(
      String expr,
      String[] inputValues,
      Object result,
      double delta) {
    String query =
        SqlTests.generateAggQuery(expr, inputValues);
    tester.check(factory, query, SqlTests.ANY_TYPE_CHECKER, result, delta);
  }

  @Override public void checkAggWithMultipleArgs(
      String expr,
      String[][] inputValues,
      Object result,
      double delta) {
    String query =
        SqlTests.generateAggQueryWithMultipleArgs(expr, inputValues);
    tester.check(factory, query, SqlTests.ANY_TYPE_CHECKER, result, delta);
  }

  @Override public void checkWinAgg(
      String expr,
      String[] inputValues,
      String windowSpec,
      String type,
      Object result,
      double delta) {
    String query =
        SqlTests.generateWinAggQuery(
            expr, windowSpec, inputValues);
    tester.check(factory, query, SqlTests.ANY_TYPE_CHECKER, result, delta);
  }

  @Override public void checkScalar(
      String expression,
      Object result,
      String resultType) {
    checkType(expression, resultType);
    tester.forEachQuery(factory, expression, sql ->
        tester.check(factory, sql, SqlTests.ANY_TYPE_CHECKER, result, 0));
  }

  @Override public void checkScalarExact(
      String expression,
      String result) {
    tester.forEachQuery(factory, expression, sql ->
        tester.check(factory, sql, SqlTests.INTEGER_TYPE_CHECKER, result, 0));
  }

  @Override public void checkScalarExact(
      String expression,
      String expectedType,
      String result) {
    final SqlTester.TypeChecker typeChecker =
        new SqlTests.StringTypeChecker(expectedType);
    tester.forEachQuery(factory, expression, sql ->
        tester.check(factory, sql, typeChecker, result, 0));
  }

  @Override public void checkScalarApprox(
      String expression,
      String expectedType,
      double expectedResult,
      double delta) {
    SqlTester.TypeChecker typeChecker =
        new SqlTests.StringTypeChecker(expectedType);
    tester.forEachQuery(factory, expression, sql ->
        tester.check(factory, sql, typeChecker, expectedResult, delta));
  }

  @Override public void checkBoolean(
      String expression,
      @Nullable Boolean result) {
    if (null == result) {
      checkNull(expression);
    } else {
      SqlTester.ResultChecker resultChecker =
          SqlTests.createChecker(result.toString(), 0);
      tester.forEachQuery(factory, expression, sql ->
          tester.check(factory, sql, SqlTests.BOOLEAN_TYPE_CHECKER,
              SqlTests.ANY_PARAMETER_CHECKER, resultChecker));
    }
  }

  @Override public void checkString(
      String expression,
      String result,
      String expectedType) {
    SqlTester.TypeChecker typeChecker =
        new SqlTests.StringTypeChecker(expectedType);
    tester.forEachQuery(factory, expression, sql ->
        tester.check(factory, sql, typeChecker, result, 0));
  }

  @Override public void checkNull(String expression) {
    tester.forEachQuery(factory, expression, sql ->
        tester.check(factory, sql, SqlTests.ANY_TYPE_CHECKER, null, 0));
  }
}
