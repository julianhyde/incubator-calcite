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
package org.apache.calcite.rel.logical;

import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelInput;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelShuttle;
import org.apache.calcite.rel.core.RelFactories;
import org.apache.calcite.rel.core.SetOp;
import org.apache.calcite.rel.core.Union;
import org.apache.calcite.sql.SqlKind;

import java.util.List;

/**
 * Sub-class of {@link org.apache.calcite.rel.core.Union}
 * not targeted at any particular engine or calling convention.
 */
public final class LogicalUnion extends Union {
  /** Factory. Also works for {@link LogicalIntersect}, {@link LogicalMinus}. */
  public static final LogicalSetOpFactory FACTORY = new LogicalSetOpFactory();

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a LogicalUnion.
   *
   * <p>Use {@link #create} unless you know what you're doing.
   */
  public LogicalUnion(RelOptCluster cluster,
      RelTraitSet traitSet,
      List<RelNode> inputs,
      boolean all) {
    super(cluster, traitSet, inputs, all);
  }

  @Deprecated // to be removed before 2.0
  public LogicalUnion(RelOptCluster cluster, List<RelNode> inputs,
      boolean all) {
    this(cluster, cluster.traitSetOf(Convention.NONE), inputs, all);
  }

  /**
   * Creates a LogicalUnion by parsing serialized output.
   */
  public LogicalUnion(RelInput input) {
    super(input);
  }

  /** Creates a LogicalUnion. */
  public static LogicalUnion create(List<RelNode> inputs, boolean all) {
    final RelOptCluster cluster = inputs.get(0).getCluster();
    final RelTraitSet traitSet = cluster.traitSetOf(Convention.NONE);
    return new LogicalUnion(cluster, traitSet, inputs, all);
  }

  //~ Methods ----------------------------------------------------------------

  public LogicalUnion copy(
      RelTraitSet traitSet, List<RelNode> inputs, boolean all) {
    assert traitSet.containsIfApplicable(Convention.NONE);
    return new LogicalUnion(getCluster(), traitSet, inputs, all);
  }

  @Override public RelNode accept(RelShuttle shuttle) {
    return shuttle.visit(this);
  }

  /**
   * Implementation of
   * {@link org.apache.calcite.rel.core.RelFactories.SetOpFactory}
   * that returns a vanilla {@link SetOp} for the particular kind of set
   * operation (UNION, EXCEPT, INTERSECT).
   */
  private static class LogicalSetOpFactory
      extends RelFactories.SetOpFactoryImpl {
    public RelNode createSetOp(SqlKind kind, List<RelNode> inputs,
        boolean all) {
      switch (kind) {
      case UNION:
        return LogicalUnion.create(inputs, all);
      case EXCEPT:
        return LogicalMinus.create(inputs, all);
      case INTERSECT:
        return LogicalIntersect.create(inputs, all);
      default:
        throw new AssertionError("not a set op: " + kind);
      }
    }
  }
}

// End LogicalUnion.java
