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

import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.PigRelBuilder;

import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

/**
 * Unit test for {@link PigRelBuilder}.
 */
public class PigRelBuilderTest {
  /** Creates a config based on the "scott" schema. */
  public static Frameworks.ConfigBuilder config() {
    return RelBuilderTest.config();
  }

  @Test public void testScan() {
    // Equivalent SQL:
    //   SELECT *
    //   FROM emp
    final PigRelBuilder builder = PigRelBuilder.create(config().build());
    final RelNode root = builder
        .scan("EMP")
        .build();
    assertThat(RelOptUtil.toString(root),
        is("LogicalTableScan(table=[[scott, EMP]])\n"));
  }

  @Test public void testCogroup() {}
  @Test public void testCross() {}
  @Test public void testCube() {}
  @Test public void testDefine() {}
  @Test public void testDistinct() {
    // Syntax:
    //   alias = DISTINCT alias [PARTITION BY partitioner] [PARALLEL n];
    final PigRelBuilder builder = PigRelBuilder.create(config().build());
    final RelNode root = builder
        .scan("EMP")
        .distinct()
        .build();
    assertThat(RelOptUtil.toString(root),
        is("LogicalAggregate(group=[{}])\n"
            + "  LogicalTableScan(table=[[scott, EMP]])\n"));
  }

  @Test public void testFilter() {
    // Syntax:
    //  FILTER name BY expr
    // Example:
    //  output_var = FILTER input_var BY (field1 is not null);
    final PigRelBuilder builder = PigRelBuilder.create(config().build());
    final RelNode root = builder
        .load("EMP.csv", null, null)
        .filter(builder.isNotNull(builder.field("MGR")))
        .build();
    assertThat(RelOptUtil.toString(root),
        is("LogicalFilter(condition=[IS NOT NULL($3)])\n"
            + "  LogicalTableScan(table=[[scott, EMP]])\n"));
  }

  @Test public void testForeach() {}
  @Test public void testGroup() {
    // Syntax:
    //   alias = GROUP alias { ALL | BY expression}
    //     [, alias ALL | BY expression …] [USING 'collected' | 'merge']
    //     [PARTITION BY partitioner] [PARALLEL n];
    // Equivalent to Pig Latin:
    //   r = GROUP e BY (deptno, job);
    final PigRelBuilder builder = PigRelBuilder.create(config().build());
    final RelNode root = builder
        .scan("EMP")
        .group(null, null, -1, builder.groupKey("DEPTNO", "JOB").alias("e"))
        .build();
    assertThat(RelOptUtil.toString(root),
        is("LogicalAggregate(group=[{2, 7}], e=[COLLECT($0, $1, $2, $3, $4, $5, $6, $7)])\n"
            + "  LogicalTableScan(table=[[scott, EMP]])\n"));
  }

  @Test public void testGroup2() {
    // Equivalent to Pig Latin:
    //   r = GROUP e BY deptno, d BY deptno;
    final PigRelBuilder builder = PigRelBuilder.create(config().build());
    final RelNode root = builder
        .scan("EMP")
        .scan("DEPT")
        .group(null, null, -1,
            builder.groupKey("DEPTNO").alias("e"),
            builder.groupKey("DEPTNO").alias("d"))
        .build();
    assertThat(RelOptUtil.toString(root),
        is("LogicalJoin(condition=[=($0, $0)], joinType=[inner])\n"
            + "  LogicalAggregate(group=[{0}], d=[COLLECT($0, $1, $2)])\n"
            + "    LogicalTableScan(table=[[scott, DEPT]])\n"
            + "  LogicalAggregate(group=[{0}], e=[COLLECT($0, $1, $2, $3, $4, $5, $6, $7)])\n"
            + "    LogicalTableScan(table=[[scott, EMP]])\n"));
  }

  @Test public void testImport() {}
  @Test public void testJoinInner() {}
  @Test public void testJoinOuter() {}
  @Test public void testLimit() {}

  @Test public void testLoad() {
    // Syntax:
    //   LOAD 'data' [USING function] [AS schema];
    // Equivalent to Pig Latin:
    //   LOAD 'EMPS.csv'
    final PigRelBuilder builder = PigRelBuilder.create(config().build());
    final RelNode root = builder
        .load("EMP.csv", null, null)
        .build();
    assertThat(RelOptUtil.toString(root),
        is("LogicalTableScan(table=[[scott, EMP]])\n"));
  }

  @Test public void testMapReduce() {}
  @Test public void testOrderBy() {}
  @Test public void testRank() {}
  @Test public void testSample() {}
  @Test public void testSplit() {}
  @Test public void testStore() {}
  @Test public void testUnion() {}
}

// End PigRelBuilderTest.java
