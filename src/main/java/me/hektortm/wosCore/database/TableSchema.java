package me.hektortm.wosCore.database;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.stream.Collectors;

public class TableSchema {
    private final String tableName;
    private final List<Column> columns = new ArrayList<>();

    public TableSchema(String tableName) {
        this.tableName = tableName;
    }

    public TableSchema addColumn(Column column) {
        columns.add(column);
        return this;
    }

    public String createTableSQL() {
        StringBuilder sb = new StringBuilder("CREATE TABLE IF NOT EXISTS " + tableName + " (");
        sb.append(columns.stream().map(Column::getSQL).collect(Collectors.joining(", ")));
        sb.append(")");
        return sb.toString();
    }

    public void migrate(Connection conn) throws SQLException {
        // Get existing columns
        Set<String> existingColumns = new HashSet<>();
        try (ResultSet rs = conn.getMetaData().getColumns(null, null, tableName, null)) {
            while (rs.next()) {
                existingColumns.add(rs.getString("COLUMN_NAME"));
            }
        }

        // Add missing columns
        try (Statement stmt = conn.createStatement()) {
            for (Column col : columns) {
                if (!existingColumns.contains(col.getName())) {
                    String sql = "ALTER TABLE " + tableName + " ADD COLUMN " + col.getSQL();
                    stmt.execute(sql);
                }
            }
        }
    }

    public String getTableName() {
        return tableName;
    }

    public List<Column> getColumns() {
        return columns;
    }
}