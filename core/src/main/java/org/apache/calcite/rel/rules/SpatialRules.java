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
package org.apache.calcite.rel.rules;

import org.apache.calcite.plan.RelOptPredicateList;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.RelFactories;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.runtime.HilbertCurve2D;
import org.apache.calcite.runtime.SpaceFillingCurve2D;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.tools.RelBuilderFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Collection of planner rules that convert
 * calls to spatial functions into more efficient expressions.
 *
 * <p>The rules allow Calcite to use spatial indexes. For example is rewritten
 *
 * <blockquote>SELECT ...
 * FROM Restaurants AS r
 * WHERE ST_DWithin(ST_Point(10, 20), ST_Point(r.latitude, r.longitude), 5)
 * </blockquote>
 *
 * <p>to
 *
 * <blockquote>SELECT ...
 * FROM Restaurants AS r
 * WHERE (r.h BETWEEN 100 AND 150
 *        OR r.h BETWEEN 170 AND 185)
 * AND ST_DWithin(ST_Point(10, 20), ST_Point(r.latitude, r.longitude), 5)
 * </blockquote>
 *
 * <p>if there is the constraint
 *
 * <blockquote>CHECK (h = Hilbert(8, r.latitude, r.longitude))</blockquote>
 *
 * <p>If the {@code Restaurants} table is sorted on {@code h} then the latter
 * query can be answered using two limited range-scans, and so is much more
 * efficient.
 *
 * <p>Note that the original predicate
 * {@code ST_DWithin(ST_Point(10, 20), ST_Point(r.latitude, r.longitude), 5)}
 * is still present, but is evaluated after the approximate predicate has
 * eliminated many potential matches.
 */
public abstract class SpatialRules {

  private SpatialRules() {}

  private static final RexUtil.RexFinder DWITHIN_FINDER =
      RexUtil.find(SqlKind.ST_DWITHIN);

  private static final RexUtil.RexFinder HILBERT_FINDER =
      RexUtil.find(SqlKind.HILBERT);

  public static final RelOptRule INSTANCE =
      new FilterHilbertRule(RelFactories.LOGICAL_BUILDER);


  /** Rule that converts ST_DWithin in a Filter condition into a predicate on
   * a Hilbert curve. */
  @SuppressWarnings("WeakerAccess")
  public static class FilterHilbertRule extends RelOptRule {
    public FilterHilbertRule(RelBuilderFactory relBuilderFactory) {
      super(
          operandJ(Filter.class, null,
              DWITHIN_FINDER.filterPredicate().and(
                  HILBERT_FINDER.filterPredicate().negate()), any()),
          relBuilderFactory, "FilterHilbertRule");
    }

    @Override public void onMatch(RelOptRuleCall call) {
      final Filter filter = call.rel(0);
      final List<RexNode> conjunctions = new ArrayList<>();
      RelOptUtil.decomposeConjunction(filter.getCondition(), conjunctions);

      // Match a predicate
      //   r.hilbert = hilbert(r.longitude, r.latitude)
      // to one of the conjunctions
      //   ST_DWithin(ST_Point(x, y), ST_Point(r.longitude, r.latitude), d)
      // and if it matches add a new conjunction before it,
      //   r.hilbert between h1 and h2
      //   or r.hilbert between h3 and h4
      // where {[h1, h2], [h3, h4]} are the ranges of the Hilbert curve
      // intersecting the rectangle
      //   (r.longitude - d, r.longitude + d, r.latitude - d, r.latitude + d)
      final int initialSize = conjunctions.size();
      final RelOptPredicateList predicates =
          call.getMetadataQuery().getAllPredicates(filter.getInput());
      for (RexNode predicate : predicates.pulledUpPredicates) {
        final RelBuilder builder = call.builder();
        if (predicate.getKind() == SqlKind.EQUALS) {
          final RexCall eqCall = (RexCall) predicate;
          if (eqCall.operands.get(0) instanceof RexInputRef
              && eqCall.operands.get(1).getKind() == SqlKind.HILBERT) {
            final RexInputRef ref  = (RexInputRef) eqCall.operands.get(0);
            final RexCall hilbertCall = (RexCall) eqCall.operands.get(1);
            final RexUtil.RexFinder finder = RexUtil.find(ref);
            if (finder.anyContain(conjunctions)) {
              // If the condition already contains "ref", it is probable that
              // this rule has already fired once.
              continue;
            }
            for (int i = 0; i < conjunctions.size(); i++) {
              final RexNode conjunction = conjunctions.get(i);
              if (conjunction.getKind() == SqlKind.ST_DWITHIN) {
                final RexCall within = (RexCall) conjunction;
                if (within.operands.get(0).getKind() == SqlKind.ST_POINT
                    && within.operands.get(1).getKind() == SqlKind.ST_POINT
                    && RexUtil.isLiteral(within.operands.get(2), true)) {
                  final RexCall point0 = (RexCall) within.operands.get(0);
                  final RexCall point1 = (RexCall) within.operands.get(1);
                  if (RexUtil.isLiteral(point0.operands.get(0), true)
                      && RexUtil.isLiteral(point0.operands.get(1), true)
                      && point1.operands.equals(hilbertCall.operands)) {
                    // Add the new predicate before the existing predicate
                    // because it is cheaper to execute (albeit less selective).
                    conjunctions.add(i++,
                        hilbertPredicate(builder.getRexBuilder(), ref,
                            point0.operands.get(0),
                            point0.operands.get(1),
                            within.operands.get(2)));
                  }
                }
              }
            }
          }
        }
        if (conjunctions.size() > initialSize) {
          call.transformTo(
              builder
                  .push(filter.getInput())
                  .filter(conjunctions)
                  .build());
        }
      }
    }

    /** Creates a predicate on the column that contains the index on the Hilbert
     * curve.
     *
     * <p>The predicate is a safe approximation. That is, it may allow some
     * points that are not within the distance, but will never disallow a point
     * that is within the distance.
     *
     * <p>Returns FALSE if the distance is negative (the ST_DWithin function
     * would always return FALSE) and returns an {@code =} predicate if distance
     * is 0. But usually returns a list of ranges,
     * {@code ref BETWEEN c1 AND c2 OR ref BETWEEN c3 AND c4}. */
    private RexNode hilbertPredicate(RexBuilder rexBuilder, RexInputRef ref,
        RexNode x, RexNode y, RexNode distance) {
      final Number xValue = (Number) RexLiteral.value(x);
      final Number yValue = (Number) RexLiteral.value(y);
      final Number dValue = (Number) RexLiteral.value(distance);
      if (dValue.doubleValue() < 0D) {
        return rexBuilder.makeLiteral(false);
      }
      final HilbertCurve2D hilbert = new HilbertCurve2D(8);
      if (dValue.doubleValue() == 0D) {
        final long index =
            hilbert.toIndex(xValue.doubleValue(), yValue.doubleValue());
        return rexBuilder.makeCall(SqlStdOperatorTable.EQUALS, ref,
            rexBuilder.makeExactLiteral(BigDecimal.valueOf(index)));
      }
      final List<SpaceFillingCurve2D.IndexRange> ranges =
          hilbert.toRanges(xValue.doubleValue() - dValue.doubleValue(),
              yValue.doubleValue() - dValue.doubleValue(),
              xValue.doubleValue() + dValue.doubleValue(),
              yValue.doubleValue() + dValue.doubleValue(),
              new SpaceFillingCurve2D.RangeComputeHints());
      final List<RexNode> nodes = new ArrayList<>();
      for (SpaceFillingCurve2D.IndexRange range : ranges) {
        final BigDecimal lowerBd = BigDecimal.valueOf(range.lower());
        final BigDecimal upperBd = BigDecimal.valueOf(range.upper());
        nodes.add(
            rexBuilder.makeCall(SqlStdOperatorTable.AND,
                rexBuilder.makeCall(SqlStdOperatorTable.GREATER_THAN_OR_EQUAL,
                    ref,
                    rexBuilder.makeExactLiteral(lowerBd)),
                rexBuilder.makeCall(SqlStdOperatorTable.LESS_THAN_OR_EQUAL,
                    ref,
                    rexBuilder.makeExactLiteral(upperBd))));
      }
      return rexBuilder.makeCall(SqlStdOperatorTable.OR, nodes);
    }
  }
}

// End SpatialRules.java
