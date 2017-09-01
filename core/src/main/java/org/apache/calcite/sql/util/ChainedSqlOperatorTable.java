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
package org.apache.calcite.sql.util;

import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.SqlSyntax;
import org.apache.calcite.sql.validate.SqlNameMatcher;

import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * ChainedSqlOperatorTable implements the {@link SqlOperatorTable} interface by
 * chaining together any number of underlying operator table instances.
 */
public class ChainedSqlOperatorTable implements SqlOperatorTable {
  //~ Instance fields --------------------------------------------------------

  protected final List<SqlOperatorTable> tableList;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a table based on a given list.
   */
  protected ChainedSqlOperatorTable(ImmutableList<SqlOperatorTable> tableList) {
    this.tableList = Objects.requireNonNull(tableList);
  }

  @Deprecated // to be removed before 2.0
  public ChainedSqlOperatorTable(List<SqlOperatorTable> tableList) {
    this(ImmutableList.copyOf(tableList));
  }

  /** Creates a composite operator table from an array of tables. */
  public static SqlOperatorTable of(SqlOperatorTable... tables) {
    return of(ImmutableList.copyOf(tables));
  }

  /** Creates a composite operator table. */
  public static SqlOperatorTable of(Iterable<? extends SqlOperatorTable> tables) {
    final ImmutableList<SqlOperatorTable> list = ImmutableList.copyOf(tables);
    if (list.size() == 1) {
      return list.get(0);
    }
    return new ChainedSqlOperatorTable(list);
  }

  //~ Methods ----------------------------------------------------------------

  /**
   * Adds an underlying table. The order in which tables are added is
   * significant; tables added earlier have higher lookup precedence. A table
   * is not added if it is already on the list.
   *
   * @param table table to add
   */
  public void add(SqlOperatorTable table) {
    if (!tableList.contains(table)) {
      tableList.add(table);
    }
  }

  public void lookupOperatorOverloads(SqlIdentifier opName,
      SqlFunctionCategory category, SqlSyntax syntax,
      List<SqlOperator> operatorList, SqlNameMatcher nameMatcher) {
    for (SqlOperatorTable table : tableList) {
      table.lookupOperatorOverloads(opName, category, syntax, operatorList,
          nameMatcher);
    }
  }

  public List<SqlOperator> getOperatorList() {
    List<SqlOperator> list = new ArrayList<>();
    for (SqlOperatorTable table : tableList) {
      list.addAll(table.getOperatorList());
    }
    return list;
  }
}
