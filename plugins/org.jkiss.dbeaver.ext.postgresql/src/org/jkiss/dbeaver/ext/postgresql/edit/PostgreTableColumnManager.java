/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2016 Serge Rieder (serge@jkiss.org)
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (version 2)
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package org.jkiss.dbeaver.ext.postgresql.edit;

import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ext.postgresql.model.*;
import org.jkiss.dbeaver.model.DBPDataKind;
import org.jkiss.dbeaver.model.DBPEvaluationContext;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.edit.DBECommandContext;
import org.jkiss.dbeaver.model.edit.DBEObjectRenamer;
import org.jkiss.dbeaver.model.edit.DBEPersistAction;
import org.jkiss.dbeaver.model.impl.DBSObjectCache;
import org.jkiss.dbeaver.model.impl.edit.DBECommandAbstract;
import org.jkiss.dbeaver.model.impl.edit.SQLDatabasePersistAction;
import org.jkiss.dbeaver.model.impl.sql.edit.struct.SQLTableColumnManager;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSDataType;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.utils.CommonUtils;

import java.sql.Types;
import java.util.List;

/**
 * Postgre table column manager
 */
public class PostgreTableColumnManager extends SQLTableColumnManager<PostgreTableColumn, PostgreTableBase> implements DBEObjectRenamer<PostgreTableColumn>  {

    protected final ColumnModifier<PostgreTableColumn> PostgreDataTypeModifier = new ColumnModifier<PostgreTableColumn>() {
        @Override
        public void appendModifier(PostgreTableColumn column, StringBuilder sql, DBECommandAbstract<PostgreTableColumn> command) {
            sql.append(' ');
            final PostgreDataType dataType = column.getDataType();
            final PostgreDataType rawType = dataType.getElementType();
            if (rawType != null) {
                sql.append(rawType.getTypeName());
            } else {
                sql.append(dataType.getTypeName());
            }
            switch (dataType.getDataKind()) {
                case STRING:
                    final long length = column.getMaxLength();
                    if (length > 0) {
                        sql.append('(').append(length).append(')');
                    }
                    break;
                case NUMERIC:
                    if (dataType.getTypeID() == Types.NUMERIC) {
                        final int precision = column.getPrecision();
                        final int scale = column.getScale();
                        if (scale > 0 || precision > 0) {
                            sql.append('(');
                            if (precision > 0) {
                                sql.append(precision);
                            }
                            if (scale > 0) {
                                if (precision > 0) {
                                    sql.append(',');
                                }
                                sql.append(scale);
                            }
                            sql.append(')');
                        }
                    }
                    break;
            }
            if (rawType != null) {
                sql.append("[]");
            }
        }
    };

    @Nullable
    @Override
    public DBSObjectCache<? extends DBSObject, PostgreTableColumn> getObjectsCache(PostgreTableColumn object)
    {
        return object.getParentObject().getContainer().tableCache.getChildrenCache(object.getParentObject());
    }

    protected ColumnModifier[] getSupportedModifiers()
    {
        return new ColumnModifier[] {PostgreDataTypeModifier, NullNotNullModifier, DefaultModifier};
    }

    @Override
    public StringBuilder getNestedDeclaration(PostgreTableBase owner, DBECommandAbstract<PostgreTableColumn> command)
    {
        StringBuilder decl = super.getNestedDeclaration(owner, command);
        final PostgreAttribute column = command.getObject();
        return decl;
    }

    private String escapeComment(String comment) {
        return comment.replace("'", "\\'");
    }

    @Override
    protected PostgreTableColumn createDatabaseObject(DBRProgressMonitor monitor, DBECommandContext context, PostgreTableBase parent, Object copyFrom)
    {
        DBSDataType columnType = findBestDataType(parent.getDataSource(), "varchar"); //$NON-NLS-1$

        final PostgreTableColumn column = new PostgreTableColumn(parent);
        column.setName(getNewColumnName(monitor, context, parent));
        final PostgreDataType dataType = parent.getDatabase().getDataType(PostgreOid.VARCHAR);
        column.setDataType(dataType); //$NON-NLS-1$
        column.setMaxLength(columnType != null && columnType.getDataKind() == DBPDataKind.STRING ? 100 : 0);
        column.setOrdinalPosition(-1);
        return column;
    }

    @Override
    protected void addObjectModifyActions(List<DBEPersistAction> actionList, ObjectChangeCommand command)
    {
        final PostgreAttribute column = command.getObject();
        // PostgreSQL can't perform all changes by one query
//        ALTER [ COLUMN ] column [ SET DATA ] TYPE data_type [ COLLATE collation ] [ USING expression ]
//        ALTER [ COLUMN ] column SET DEFAULT expression
//        ALTER [ COLUMN ] column DROP DEFAULT
//        ALTER [ COLUMN ] column { SET | DROP } NOT NULL
//        ALTER [ COLUMN ] column SET STATISTICS integer
//        ALTER [ COLUMN ] column SET ( attribute_option = value [, ... ] )
//        ALTER [ COLUMN ] column RESET ( attribute_option [, ... ] )
//        ALTER [ COLUMN ] column SET STORAGE { PLAIN | EXTERNAL | EXTENDED | MAIN }
        String prefix = "ALTER TABLE " + DBUtils.getObjectFullName(column.getTable(), DBPEvaluationContext.DDL) + " ALTER COLUMN " + DBUtils.getQuotedIdentifier(column) + " ";
        String typeClause = column.getFullTypeName();
        if (column.getDataType() != null) {
            typeClause += " USING " + DBUtils.getQuotedIdentifier(column) + "::" + column.getDataType().getName();
        }
        actionList.add(new SQLDatabasePersistAction("Set column type", prefix + "TYPE " + typeClause));
        actionList.add(new SQLDatabasePersistAction("Set column nullability", prefix + (column.isRequired() ? "SET" : "DROP") + " NOT NULL"));

        if (CommonUtils.isEmpty(column.getDefaultValue())) {
            actionList.add(new SQLDatabasePersistAction("Drop column default", prefix + "DROP DEFAULT"));
        } else {
            actionList.add(new SQLDatabasePersistAction("Set column default", prefix + "SET DEFAULT " + column.getDefaultValue()));
        }
    }

    @Override
    public void renameObject(DBECommandContext commandContext, PostgreTableColumn object, String newName) throws DBException {
        processObjectRename(commandContext, object, newName);
    }

    @Override
    protected void addObjectRenameActions(List<DBEPersistAction> actions, ObjectRenameCommand command)
    {
        final PostgreAttribute column = command.getObject();

        actions.add(
            new SQLDatabasePersistAction(
                "Rename column",
                "ALTER TABLE " + DBUtils.getObjectFullName(column.getTable(), DBPEvaluationContext.DDL) + " RENAME COLUMN " +
                    DBUtils.getQuotedIdentifier(column.getDataSource(), command.getOldName()) + " TO " +
                    DBUtils.getQuotedIdentifier(column.getDataSource(), command.getNewName())));
    }

}
