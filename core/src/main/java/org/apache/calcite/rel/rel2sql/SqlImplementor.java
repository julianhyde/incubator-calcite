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
package org.apache.calcite.rel.rel2sql;

import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexLocalRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexProgram;
import org.apache.calcite.sql.SqlBinaryOperator;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlDataTypeSpec;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlSelectKeyword;
import org.apache.calcite.sql.SqlSetOperator;
import org.apache.calcite.sql.fun.SqlCase;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.fun.SqlSumEmptyIsZeroAggFunction;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.BasicSqlType;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.type.SqlTypeUtil;
import org.apache.calcite.sql.validate.SqlValidatorUtil;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.Util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * State for generating a SQL statement.
 */
public class SqlImplementor {
  public static final SqlParserPos POS = SqlParserPos.ZERO;

  /** Oracle's {@code SUBSTR} function.
   * Oracle does not support {@link SqlStdOperatorTable#SUBSTRING}. */
  public static final SqlFunction ORACLE_SUBSTR =
      new SqlFunction("SUBSTR", SqlKind.OTHER_FUNCTION,
          ReturnTypes.ARG0_NULLABLE_VARYING, null, null,
          SqlFunctionCategory.STRING);

  public final SqlDialect dialect;
  protected final Set<String> aliasSet = new LinkedHashSet<>();
  protected final Map<String, SqlNode> ordinalMap = new HashMap<>();

  protected SqlImplementor(SqlDialect dialect) {
    this.dialect = dialect;
  }

  /** Creates a result based on a single relational expression. */
  public Result result(SqlNode node, Collection<Clause> clauses, RelNode rel) {
    final String alias2 = SqlValidatorUtil.getAlias(node, -1);
    final String alias3 = alias2 != null ? alias2 : "t";
    final String alias4 =
        SqlValidatorUtil.uniquify(
            alias3, aliasSet, SqlValidatorUtil.EXPR_SUGGESTER);
    final String alias5 = alias2 == null || !alias2.equals(alias4) ? alias4
        : null;
    return new Result(node, clauses, alias5,
        Collections.singletonList(Pair.of(alias4, rel.getRowType())));
  }

  /** Creates a result based on a join. (Each join could contain one or more
   * relational expressions.) */
  public Result result(SqlNode join, Result leftResult, Result rightResult) {
    final List<Pair<String, RelDataType>> list = new ArrayList<>();
    list.addAll(leftResult.aliases);
    list.addAll(rightResult.aliases);
    return new Result(join, Expressions.list(Clause.FROM), null, list);
  }

  /** Wraps a node in a SELECT statement that has no clauses:
   *  "SELECT ... FROM (node)". */
  SqlSelect wrapSelect(SqlNode node) {
    assert node instanceof SqlJoin
        || node instanceof SqlIdentifier
        || node instanceof SqlCall
        && (((SqlCall) node).getOperator() instanceof SqlSetOperator
        || ((SqlCall) node).getOperator() == SqlStdOperatorTable.AS)
        : node;
    return new SqlSelect(POS, SqlNodeList.EMPTY, null, node, null, null, null,
        SqlNodeList.EMPTY, null, null, null);
  }

  /** Context for translating a {@link RexNode} expression (within a
   * {@link RelNode}) into a {@link SqlNode} expression (within a SQL parse
   * tree). */
  public abstract class Context {
    final int fieldCount;
    private final boolean ignoreCast;

    protected Context(int fieldCount) {
      this(fieldCount, false);
    }

    protected Context(int fieldCount, boolean ignoreCast) {
      this.fieldCount = fieldCount;
      this.ignoreCast = ignoreCast;
    }

    public abstract SqlNode field(int ordinal);

    /** Converts an expression from {@link RexNode} to {@link SqlNode}
     * format. */
    public SqlNode toSql(RexProgram program, RexNode rex) {
      switch (rex.getKind()) {
      case LOCAL_REF:
        final int index = ((RexLocalRef) rex).getIndex();
        return toSql(program, program.getExprList().get(index));

      case INPUT_REF:
        return field(((RexInputRef) rex).getIndex());

      case LITERAL:
        final RexLiteral literal = (RexLiteral) rex;
        if (literal.getTypeName() == SqlTypeName.SYMBOL) {
          final SqlLiteral.SqlSymbol symbol =
              (SqlLiteral.SqlSymbol) literal.getValue();
          return SqlLiteral.createSymbol(symbol, POS);
        }
        switch (literal.getTypeName().getFamily()) {
        case CHARACTER:
          return SqlLiteral.createCharString((String) literal.getValue2(), POS);
        case NUMERIC:
        case EXACT_NUMERIC:
          return SqlLiteral.createExactNumeric(literal.getValue().toString(),
              POS);
        case APPROXIMATE_NUMERIC:
          return SqlLiteral.createApproxNumeric(
              literal.getValue().toString(), POS);
        case BOOLEAN:
          return SqlLiteral.createBoolean((Boolean) literal.getValue(), POS);
        case DATE:
          return SqlLiteral.createDate((Calendar) literal.getValue(), POS);
        case TIME:
          return SqlLiteral.createTime((Calendar) literal.getValue(),
              literal.getType().getPrecision(), POS);
        case TIMESTAMP:
          return SqlLiteral.createTimestamp((Calendar) literal.getValue(),
              literal.getType().getPrecision(), POS);
        case ANY:
        case NULL:
          switch (literal.getTypeName()) {
          case NULL:
            return SqlLiteral.createNull(POS);
          // fall through
          }
        default:
          throw new AssertionError(literal + ": " + literal.getTypeName());
        }
      case CASE:
        final RexCall caseCall = (RexCall) rex;
        final List<SqlNode> caseNodeList =
            toSql(program, caseCall.getOperands());
        final SqlNode valueNode;
        final List<SqlNode> whenList = Expressions.list();
        final List<SqlNode> thenList = Expressions.list();
        final SqlNode elseNode;
        if (caseNodeList.size() % 2 == 0) {
          // switched:
          //   "case x when v1 then t1 when v2 then t2 ... else e end"
          valueNode = caseNodeList.get(0);
          for (int i = 1; i < caseNodeList.size() - 1; i += 2) {
            whenList.add(caseNodeList.get(i));
            thenList.add(caseNodeList.get(i + 1));
          }
        } else {
          // other: "case when w1 then t1 when w2 then t2 ... else e end"
          valueNode = null;
          for (int i = 0; i < caseNodeList.size() - 1; i += 2) {
            whenList.add(caseNodeList.get(i));
            thenList.add(caseNodeList.get(i + 1));
          }
        }
        elseNode = caseNodeList.get(caseNodeList.size() - 1);
        return new SqlCase(POS, valueNode, new SqlNodeList(whenList, POS),
            new SqlNodeList(thenList, POS), elseNode);

      default:
        final RexCall call = (RexCall) rex;
        final SqlOperator op = call.getOperator();
        final List<SqlNode> nodeList = toSql(program, call.getOperands());
        switch (rex.getKind()) {
        case CAST:
          if (ignoreCast) {
            assert nodeList.size() == 1;
            return nodeList.get(0);
          } else {
            nodeList.add(toSql(call.getType()));
          }
        }
        if (op instanceof SqlBinaryOperator && nodeList.size() > 2) {
          // In RexNode trees, OR and AND have any number of children;
          // SqlCall requires exactly 2. So, convert to a left-deep binary tree.
          return createLeftCall(op, nodeList);
        }
        if (op == SqlStdOperatorTable.SUBSTRING) {
          switch (dialect.getDatabaseProduct()) {
          case ORACLE:
            return ORACLE_SUBSTR.createCall(new SqlNodeList(nodeList, POS));
          }
        }
        return op.createCall(new SqlNodeList(nodeList, POS));
      }
    }

    private SqlNode createLeftCall(SqlOperator op, List<SqlNode> nodeList) {
      if (nodeList.size() == 2) {
        return op.createCall(new SqlNodeList(nodeList, POS));
      }
      final List<SqlNode> butLast = Util.skipLast(nodeList);
      final SqlNode last = nodeList.get(nodeList.size() - 1);
      final SqlNode call = createLeftCall(op, butLast);
      return op.createCall(new SqlNodeList(ImmutableList.of(call, last), POS));
    }

    private SqlNode toSql(RelDataType type) {
      switch (dialect.getDatabaseProduct()) {
      case MYSQL:
        switch (type.getSqlTypeName()) {
        case VARCHAR:
          // MySQL doesn't have a VARCHAR type, only CHAR.
          return new SqlDataTypeSpec(new SqlIdentifier("CHAR", POS),
              type.getPrecision(), -1, null, null, POS);
        case INTEGER:
          return new SqlDataTypeSpec(new SqlIdentifier("_UNSIGNED", POS),
              type.getPrecision(), -1, null, null, POS);
        }
        break;
      }
      if (type instanceof BasicSqlType) {
        return new SqlDataTypeSpec(
            new SqlIdentifier(type.getSqlTypeName().name(), POS),
            type.getPrecision(),
            type.getScale(),
            type.getCharset() != null
            && dialect.supportsCharSet()
                ? type.getCharset().name()
                : null,
            null,
            POS);
      }
      return SqlTypeUtil.convertTypeToSpec(type);
    }

    private List<SqlNode> toSql(RexProgram program, List<RexNode> operandList) {
      final List<SqlNode> list = new ArrayList<>();
      for (RexNode rex : operandList) {
        list.add(toSql(program, rex));
      }
      return list;
    }

    public List<SqlNode> fieldList() {
      return new AbstractList<SqlNode>() {
        public SqlNode get(int index) {
          return field(index);
        }

        public int size() {
          return fieldCount;
        }
      };
    }

    /** Converts a call to an aggregate function to an expression. */
    public SqlNode toSql(AggregateCall aggCall) {
      SqlOperator op = aggCall.getAggregation();
      if (op instanceof SqlSumEmptyIsZeroAggFunction) {
        op = SqlStdOperatorTable.SUM;
      }
      final List<SqlNode> operands = Expressions.list();
      for (int arg : aggCall.getArgList()) {
        operands.add(field(arg));
      }
      return op.createCall(
          aggCall.isDistinct() ? SqlSelectKeyword.DISTINCT.symbol(POS) : null,
          POS, operands.toArray(new SqlNode[operands.size()]));
    }

    /** Converts a collation to an ORDER BY item. */
    public SqlNode toSql(RelFieldCollation collation) {
      SqlNode node = field(collation.getFieldIndex());
      switch (collation.getDirection()) {
      case DESCENDING:
      case STRICTLY_DESCENDING:
        node = SqlStdOperatorTable.DESC.createCall(POS, node);
      }
      if (collation.nullDirection != dialect.defaultNullDirection(collation.direction)) {
        switch (collation.nullDirection) {
        case FIRST:
          node = SqlStdOperatorTable.NULLS_FIRST.createCall(POS, node);
          break;
        case LAST:
          node = SqlStdOperatorTable.NULLS_LAST.createCall(POS, node);
          break;
        }
      }
      return node;
    }

    public SqlImplementor implementor() {
      return SqlImplementor.this;
    }
  }

  private static int computeFieldCount(
      List<Pair<String, RelDataType>> aliases) {
    int x = 0;
    for (Pair<String, RelDataType> alias : aliases) {
      x += alias.right.getFieldCount();
    }
    return x;
  }

  public Context aliasContext(List<Pair<String, RelDataType>> aliases,
      boolean qualified) {
    return new AliasContext(aliases, qualified);
  }

  public Context joinContext(Context leftContext, Context rightContext) {
    return new JoinContext(leftContext, rightContext);
  }

  /** Implementation of Context that precedes field references with their
   * "table alias" based on the current sub-query's FROM clause. */
  public class AliasContext extends Context {
    private final boolean qualified;
    private final List<Pair<String, RelDataType>> aliases;

    /** Creates an AliasContext; use {@link #aliasContext(List, boolean)}. */
    protected AliasContext(List<Pair<String, RelDataType>> aliases,
        boolean qualified) {
      super(computeFieldCount(aliases));
      this.aliases = aliases;
      this.qualified = qualified;
    }

    public SqlNode field(int ordinal) {
      for (Pair<String, RelDataType> alias : aliases) {
        final List<RelDataTypeField> fields = alias.right.getFieldList();
        if (ordinal < fields.size()) {
          RelDataTypeField field = fields.get(ordinal);
          final SqlNode mappedSqlNode =
              ordinalMap.get(field.getName().toLowerCase());
          if (mappedSqlNode != null) {
            return mappedSqlNode;
          }
          return new SqlIdentifier(!qualified
              ? ImmutableList.of(field.getName())
              : ImmutableList.of(alias.left, field.getName()),
              POS);
        }
        ordinal -= fields.size();
      }
      throw new AssertionError(
          "field ordinal " + ordinal + " out of range " + aliases);
    }
  }

  /** Context for translating ON clause of a JOIN from {@link RexNode} to
   * {@link SqlNode}. */
  class JoinContext extends Context {
    private final SqlImplementor.Context leftContext;
    private final SqlImplementor.Context rightContext;

    /** Creates a JoinContext; use {@link #joinContext(Context, Context)}. */
    private JoinContext(Context leftContext, Context rightContext) {
      super(leftContext.fieldCount + rightContext.fieldCount);
      this.leftContext = leftContext;
      this.rightContext = rightContext;
    }

    public SqlNode field(int ordinal) {
      if (ordinal < leftContext.fieldCount) {
        return leftContext.field(ordinal);
      } else {
        return rightContext.field(ordinal - leftContext.fieldCount);
      }
    }
  }

  /** Result of implementing a node. */
  public class Result {
    final SqlNode node;
    private final String neededAlias;
    private final List<Pair<String, RelDataType>> aliases;
    final Expressions.FluentList<Clause> clauses;

    public Result(SqlNode node, Collection<Clause> clauses, String neededAlias,
        List<Pair<String, RelDataType>> aliases) {
      this.node = node;
      this.neededAlias = neededAlias;
      this.aliases = aliases;
      this.clauses = Expressions.list(clauses);
    }

    /** Once you have a Result of implementing a child relational expression,
     * call this method to create a Builder to implement the current relational
     * expression by adding additional clauses to the SQL query.
     *
     * <p>You need to declare which clauses you intend to add. If the clauses
     * are "later", you can add to the same query. For example, "GROUP BY" comes
     * after "WHERE". But if they are the same or earlier, this method will
     * start a new SELECT that wraps the previous result.
     *
     * <p>When you have called
     * {@link Builder#setSelect(SqlNodeList)},
     * {@link Builder#setWhere(SqlNode)} etc. call
     * {@link Builder#result(SqlNode, Collection, RelNode)}
     * to fix the new query.
     *
     * @param rel Relational expression being implemented
     * @param clauses Clauses that will be generated to implement current
     *                relational expression
     * @return A builder
     */
    public Builder builder(RelNode rel, Clause... clauses) {
      final Clause maxClause = maxClause();
      boolean needNew = false;
      // If old and new clause are equal and belong to below set,
      // then new SELECT wrap is not required
      Set<Clause> nonWrapSet = ImmutableSet.of(Clause.SELECT);
      for (Clause clause : clauses) {
        if (maxClause.ordinal() > clause.ordinal()
            || (maxClause.equals(clause) && !nonWrapSet.contains(clause))) {
          needNew = true;
        }
      }
      SqlSelect select;
      Expressions.FluentList<Clause> clauseList = Expressions.list();
      if (needNew) {
        select = subSelect();
      } else {
        select = asSelect();
        clauseList.addAll(this.clauses);
      }
      clauseList.appendAll(clauses);
      Context newContext;
      final SqlNodeList selectList = select.getSelectList();
      if (selectList != null) {
        newContext = new Context(selectList.size()) {
          public SqlNode field(int ordinal) {
            final SqlNode selectItem = selectList.get(ordinal);
            switch (selectItem.getKind()) {
            case AS:
              return ((SqlCall) selectItem).operand(0);
            }
            return selectItem;
          }
        };
      } else {
        newContext = aliasContext(aliases, aliases.size() > 1);
      }
      return new Builder(rel, clauseList, select, newContext);
    }

    // make private?
    public Clause maxClause() {
      Clause maxClause = null;
      for (Clause clause : clauses) {
        if (maxClause == null || clause.ordinal() > maxClause.ordinal()) {
          maxClause = clause;
        }
      }
      assert maxClause != null;
      return maxClause;
    }

    /** Returns a node that can be included in the FROM clause or a JOIN. It has
     * an alias that is unique within the query. The alias is implicit if it
     * can be derived using the usual rules (For example, "SELECT * FROM emp" is
     * equivalent to "SELECT * FROM emp AS emp".) */
    public SqlNode asFrom() {
      if (neededAlias != null) {
        return SqlStdOperatorTable.AS.createCall(POS, node,
            new SqlIdentifier(neededAlias, POS));
      }
      return node;
    }

    public SqlSelect subSelect() {
      return wrapSelect(asFrom());
    }

    /** Converts a non-query node into a SELECT node. Set operators (UNION,
     * INTERSECT, EXCEPT) remain as is. */
    public SqlSelect asSelect() {
      if (node instanceof SqlSelect) {
        return (SqlSelect) node;
      }
      return wrapSelect(node);
    }

    /** Converts a non-query node into a SELECT node. Set operators (UNION,
     * INTERSECT, EXCEPT) remain as is. */
    public SqlNode asQuery() {
      if (node instanceof SqlCall
          && ((SqlCall) node).getOperator() instanceof SqlSetOperator) {
        return node;
      }
      return asSelect();
    }

    /** Returns a context that always qualifies identifiers. Useful if the
     * Context deals with just one arm of a join, yet we wish to generate
     * a join condition that qualifies column names to disambiguate them. */
    public Context qualifiedContext() {
      return aliasContext(aliases, true);
    }
  }

  /** Builder. */
  public class Builder {
    private final RelNode rel;
    final List<Clause> clauses;
    private final SqlSelect select;
    public final Context context;

    public Builder(RelNode rel, List<Clause> clauses, SqlSelect select,
        Context context) {
      this.rel = rel;
      this.clauses = clauses;
      this.select = select;
      this.context = context;
    }

    public void setSelect(SqlNodeList nodeList) {
      select.setSelectList(nodeList);
    }

    public void setWhere(SqlNode node) {
      assert clauses.contains(Clause.WHERE);
      select.setWhere(node);
    }

    public void setGroupBy(SqlNodeList nodeList) {
      assert clauses.contains(Clause.GROUP_BY);
      select.setGroupBy(nodeList);
    }

    public void setOrderBy(SqlNodeList nodeList) {
      assert clauses.contains(Clause.ORDER_BY);
      select.setOrderBy(nodeList);
    }

    public void setFetch(SqlNode fetch) {
      assert clauses.contains(Clause.FETCH);
      select.setFetch(fetch);
    }

    public void setOffset(SqlNode offset) {
      assert clauses.contains(Clause.OFFSET);
      select.setOffset(offset);
    }

    public Result result() {
      return SqlImplementor.this.result(select, clauses, rel);
    }
  }

  /** Clauses in a SQL query. Ordered by evaluation order.
   * SELECT is set only when there is a NON-TRIVIAL SELECT clause. */
  public enum Clause {
    FROM, WHERE, GROUP_BY, HAVING, SELECT, SET_OP, ORDER_BY, FETCH, OFFSET
  }
}

// End SqlImplementor.java
