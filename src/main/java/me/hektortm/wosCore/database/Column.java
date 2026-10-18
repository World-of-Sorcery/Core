package me.hektortm.wosCore.database;

public class Column {
    private final String name;
    private final String type;
    private final boolean primaryKey;
    private final boolean notNull;
    private final String defaultValue;

    public Column(String name, String type, boolean primaryKey, boolean notNull, String defaultValue) {
        this.name = name;
        this.type = type;
        this.primaryKey = primaryKey;
        this.notNull = notNull;
        this.defaultValue = defaultValue;
    }

    public String getName() {
        return name;
    }

    /**
     * Generates the SQL fragment for this column, e.g.
     * "id VARCHAR(40) NOT NULL PRIMARY KEY DEFAULT 'xyz'"
     */
    public String getSQL() {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append(" ").append(type);

        if (notNull) {
            sb.append(" NOT NULL");
        }

        if (defaultValue != null) {
            // Add quotes if type is text-like
            if (type.toUpperCase().contains("CHAR") || type.toUpperCase().contains("TEXT") || type.toUpperCase().contains("JSON")) {
                sb.append(" DEFAULT '").append(defaultValue).append("'");
            } else {
                sb.append(" DEFAULT ").append(defaultValue);
            }
        }

        if (primaryKey) {
            sb.append(" PRIMARY KEY");
        }

        return sb.toString();
    }
}
