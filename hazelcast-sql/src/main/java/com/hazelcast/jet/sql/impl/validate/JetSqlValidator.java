/*
 * Copyright (c) 2008-2021, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.sql.impl.validate;

import com.hazelcast.jet.sql.impl.connector.SqlConnector;
import com.hazelcast.jet.sql.impl.parse.SqlCreateJob;
import com.hazelcast.jet.sql.impl.parse.SqlShowStatement;
import com.hazelcast.jet.sql.impl.schema.JetTableFunction;
import com.hazelcast.sql.impl.QueryException;
import com.hazelcast.sql.impl.calcite.schema.HazelcastTable;
import com.hazelcast.sql.impl.calcite.validate.HazelcastSqlValidator;
import com.hazelcast.sql.impl.calcite.validate.types.HazelcastTypeFactory;
import com.hazelcast.sql.impl.schema.Table;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlDelete;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlInsert;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.util.SqlBasicVisitor;
import org.apache.calcite.sql.validate.SqlConformance;
import org.apache.calcite.sql.validate.SqlValidatorCatalogReader;
import org.apache.calcite.sql.validate.SqlValidatorScope;
import org.apache.calcite.sql.validate.SqlValidatorTable;
import org.apache.calcite.sql.validate.SqlValidatorUtil;

import java.util.List;

import static com.hazelcast.jet.sql.impl.connector.SqlConnectorUtil.getJetSqlConnector;
import static com.hazelcast.jet.sql.impl.validate.ValidatorResource.RESOURCE;
import static org.apache.calcite.sql.SqlKind.AGGREGATE;
import static org.apache.calcite.sql.SqlKind.VALUES;

public class JetSqlValidator extends HazelcastSqlValidator {

    private boolean isCreateJob;
    private boolean isInfiniteRows;

    public JetSqlValidator(
            SqlValidatorCatalogReader catalogReader,
            HazelcastTypeFactory typeFactory,
            SqlConformance conformance,
            List<Object> arguments
    ) {
        super(JetSqlOperatorTable.instance(), catalogReader, typeFactory, conformance, arguments);
    }

    @Override
    public SqlNode validate(SqlNode topNode) {
        if (topNode instanceof SqlCreateJob) {
            isCreateJob = true;
        }

        if (topNode.getKind().belongsTo(SqlKind.DDL)) {
            topNode.validate(this, getEmptyScope());
            return topNode;
        }

        if (topNode instanceof SqlShowStatement) {
            return topNode;
        }

        return super.validate(topNode);
    }

    @Override
    protected void validateSelect(SqlSelect select, SqlValidatorScope scope) {
        // Derive the types for offset-fetch expressions, Calcite doesn't do
        // that automatically.

        SqlNode fetch = select.getFetch();
        if (fetch != null) {
            deriveType(scope, fetch);
            fetch.validate(this, getEmptyScope());
        }
    }

    @Override
    protected void validateFrom(SqlNode node, RelDataType targetRowType, SqlValidatorScope scope) {
        super.validateFrom(node, targetRowType, scope);
        isInfiniteRows = containsStreamingSource(node);
    }

    @Override
    public void validateInsert(SqlInsert insert) {
        super.validateInsert(insert);

        if (!isCreateJob && isInfiniteRows(insert.getSource())) {
            throw newValidationError(insert, RESOURCE.mustUseCreateJob());
        }
    }

    @Override
    protected void validateGroupClause(SqlSelect select) {
        super.validateGroupClause(select);

        if (containsGroupingOrAggregation(select) && isInfiniteRows(select)) {
            throw newValidationError(select, RESOURCE.streamingAggregationsNotSupported());
        }
    }

    @Override
    protected void validateOrderList(SqlSelect select) {
        super.validateOrderList(select);

        if (select.hasOrderBy() && isInfiniteRows(select)) {
            throw newValidationError(select, RESOURCE.streamingSortingNotSupported());
        }
    }

    private boolean containsGroupingOrAggregation(SqlSelect select) {
        if (select.getGroup() != null && select.getGroup().size() > 0) {
            return true;
        }

        if (select.isDistinct()) {
            return true;
        }

        for (SqlNode node : select.getSelectList()) {
            if (node.getKind().belongsTo(AGGREGATE)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Goes over all the referenced tables in the given {@link SqlNode}
     * and returns true if any of them uses a streaming connector.
     */
    private boolean containsStreamingSource(SqlNode node) {
        class FindStreamingTablesVisitor extends SqlBasicVisitor<Void> {
            boolean found;

            @Override
            public Void visit(SqlIdentifier id) {
                SqlValidatorTable table = getCatalogReader().getTable(id.names);
                if (table != null) { // not every identifier is a table
                    HazelcastTable hazelcastTable = table.unwrap(HazelcastTable.class);
                    SqlConnector connector = getJetSqlConnector(hazelcastTable.getTarget());
                    if (connector.isStream()) {
                        found = true;
                        return null;
                    }
                }
                return super.visit(id);
            }

            @Override
            public Void visit(SqlCall call) {
                SqlOperator operator = call.getOperator();
                if (operator instanceof JetTableFunction) {
                    if (((JetTableFunction) operator).isStream()) {
                        found = true;
                        return null;
                    }
                }
                return super.visit(call);
            }
        }

        FindStreamingTablesVisitor visitor = new FindStreamingTablesVisitor();
        node.accept(visitor);
        return visitor.found;
    }

    @Override
    protected void validateJoin(SqlJoin join, SqlValidatorScope scope) {
        super.validateJoin(join, scope);

        // the right side of a join must not be a subquery or a VALUES clause
        join.getRight().accept(new SqlBasicVisitor<Void>() {
            @Override
            public Void visit(SqlCall call) {
                if (call.getKind() == SqlKind.SELECT) {
                    throw newValidationError(join, RESOURCE.joiningSubqueryNotSupported());
                } else if (call.getKind() == VALUES) {
                    throw newValidationError(join, RESOURCE.joiningValuesNotSupported());
                }

                return call.getOperator().acceptCall(this, call);
            }
        });
    }

    @Override
    public boolean isInfiniteRows() {
        return isInfiniteRows;
    }

    private boolean isInfiniteRows(SqlNode node) {
        isInfiniteRows |= containsStreamingSource(node);
        return isInfiniteRows;
    }

    @Override
    protected SqlSelect createSourceSelectForDelete(SqlDelete call) {
        SqlNode sourceTable = call.getTargetTable();
        Table table = getCatalogReader().getTable(((SqlIdentifier) sourceTable).names).unwrap(HazelcastTable.class).getTarget();
        SqlConnector connector = getJetSqlConnector(table);

        // The Calcite default implementation selects all fields (using SELECT *). I'm not sure about how's this supposed
        // to work. We need to feed primary keys to the delete processor so that it can directly delete the records.
        // Therefore we use the primary key for the select list.
        SqlNodeList selectList = connector.getPrimaryKey(table);
        if (selectList.size() == 0) {
            throw QueryException.error("Cannot DELETE from " + call.getTargetTable() + ": it doesn't have a primary key");
        }

        if (call.getAlias() != null) {
          sourceTable =
              SqlValidatorUtil.addAlias(
                  sourceTable,
                  call.getAlias().getSimple());
        }
        return new SqlSelect(SqlParserPos.ZERO, null, selectList, sourceTable,
            call.getCondition(), null, null, null, null, null, null, null);
    }
}
