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
package org.apache.calcite.sql2rel;

import org.apache.calcite.rel.RelHomogeneousShuttle;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.CorrelationId;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCorrelVariable;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;

import com.google.common.collect.ImmutableSet;

/**
 * Rewrites relations to ensure the same correlation is referenced by the same
 * correlation variable.
 */
public class DeduplicateCorrelateVariables extends RelHomogeneousShuttle {
  private final RexShuttle dedupRex;

  /**
   * Replaces alternative names of correlation variable to its canonical name.
   */
  private static class DeduplicateCorrelateVariablesShuttle extends RexShuttle {
    private final RexBuilder builder;
    private final CorrelationId id;
    private final ImmutableSet<CorrelationId> altIds;

    public DeduplicateCorrelateVariablesShuttle(RexBuilder builder,
        CorrelationId canonical, ImmutableSet<CorrelationId> altIds) {
      this.builder = builder;
      this.id = canonical;
      this.altIds = altIds;
    }

    @Override public RexNode visitCorrelVariable(RexCorrelVariable variable) {
      if (!altIds.contains(variable.id)) {
        return variable;
      }

      return builder.makeCorrel(variable.getType(), id);
    }
  }

  public DeduplicateCorrelateVariables(RexBuilder builder,
      CorrelationId canonical, ImmutableSet<CorrelationId> altNames) {
    dedupRex = new DeduplicateCorrelateVariablesShuttle(builder,
        canonical, altNames);
  }

  @Override public RelNode visit(RelNode other) {
    RelNode next = super.visit(other);
    return next.accept(dedupRex);
  }
}

// End DeduplicateCorrelateVariables.java
