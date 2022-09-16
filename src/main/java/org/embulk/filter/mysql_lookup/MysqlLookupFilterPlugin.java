package org.embulk.filter.mysql_lookup;
import com.google.common.collect.ImmutableList;
import org.embulk.config.Config;
import org.embulk.config.ConfigSource;
import org.embulk.config.Task;
import org.embulk.config.TaskSource;
import org.embulk.spi.*;
import org.embulk.spi.time.Timestamp;
import org.embulk.spi.type.Types;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MysqlLookupFilterPlugin
        implements FilterPlugin {
    public interface PluginTask
            extends Task {
        @Config("host")
        public String getHost();

        @Config("port")
        public String getPort();

        @Config("database")
        public String getDatabase();

        @Config("tablename")
        public String getTableName();

        @Config("username")
        public String getUserName();

        @Config("password")
        public String getPassword();

        @Config("mapping_from")
        public List<String> getMappingFrom();

        @Config("mapping_to")
        public List<String> getMappingTo();

        @Config("new_columns")
        public SchemaConfig getNewColumns();

    }

    @Override
    public void transaction(ConfigSource config, Schema inputSchema,
                            FilterPlugin.Control control) {
        PluginTask task = config.loadConfig(PluginTask.class);

        List<String> inputColumns = task.getMappingFrom();
        List<String> keyColumns = task.getMappingTo();
        if (inputColumns.size() != keyColumns.size()) {
            throw new RuntimeException("Number of mapping_from columns must be exactly equals to number of mapping_to columns");
        }

        Schema outputSchema = inputSchema;

        ImmutableList.Builder<Column> builder = ImmutableList.builder();
        int i = 0;
        for (Column inputColumn : inputSchema.getColumns()) {
            Column outputColumn = new Column(i++, inputColumn.getName(), inputColumn.getType());
            builder.add(outputColumn);
        }

        for (ColumnConfig columnConfig : task.getNewColumns().getColumns()) {
            builder.add(columnConfig.toColumn(i++));
        }
        outputSchema = new Schema(builder.build());

        control.run(task.dump(), outputSchema);
    }

    @Override
    public PageOutput open(TaskSource taskSource, Schema inputSchema,
                           Schema outputSchema, PageOutput output) {
        PluginTask task = taskSource.loadTask(PluginTask.class);
        Map<String, List<String>> map = new HashMap<>();
        try {
            map = getKeyValueMap(task);
        } catch (SQLException e) {
            e.printStackTrace();
        }
        PageReader pageReader = new PageReader(inputSchema);
        return new MyOutput(pageReader, inputSchema, outputSchema, output, task, map);
    }


    private Map<String, List<String>> getKeyValueMap(PluginTask task) throws SQLException {
        Map<String, List<String>> map = new HashMap<>();
        Connection con = DatabaseConnection.getConnection(task);
        try {

            List<String> targetColumns = task.getMappingTo();
            List<String> newColumns = new ArrayList<>();

            for (ColumnConfig columnConfig : task.getNewColumns().getColumns()) {
                newColumns.add(columnConfig.getName());
            }

            String query = "select ";
            String columnNeedsToBeFetched = "";
            for (int i = 0; i < targetColumns.size(); i++) {
                columnNeedsToBeFetched += targetColumns.get(i) + ",";
            }
            for (int i = 0; i < newColumns.size(); i++) {
                if (i != newColumns.size() - 1) {
                    columnNeedsToBeFetched += newColumns.get(i) + ",";
                } else {
                    columnNeedsToBeFetched += newColumns.get(i);
                }
            }
            query += columnNeedsToBeFetched + " from " + task.getTableName();

            Statement stmt = con.createStatement();
            ResultSet rs = stmt.executeQuery(query);

            while (rs.next()) {

                //for key
                String key = "";
                String comp = "";
                for (int i = 0; i < targetColumns.size(); i++) {
                    comp = "" + rs.getString(targetColumns.get(i));
                    if (comp.equalsIgnoreCase("null")) {
                        key += "";
                    } else {
                        key += rs.getString(targetColumns.get(i));
                    }
                    if (i != targetColumns.size() - 1) {
                        key += ",";
                    }
                }

                //for values
                List<String> keyArray = new ArrayList<>();
                for (int i = 0; i < newColumns.size(); i++) {
                    comp = "" + rs.getString(newColumns.get(i));
                    if (comp.equalsIgnoreCase("null")) {
                        keyArray.add("");
                    } else {
                        keyArray.add(rs.getString(newColumns.get(i)));
                    }
                }
                map.put(key, keyArray);

            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            con.close();
        }
        return map;
    }


    public static class MyOutput implements PageOutput {
        private PageReader reader;
        private PageBuilder builder;
        private PluginTask task;
        private Schema inputSchema;
        private Map<String, List<String>> keyValuePair;

        public MyOutput(PageReader pageReader, Schema inputSchema, Schema outputSchema, PageOutput pageOutput, PluginTask task, Map<String, List<String>> keyValuePair) {
            this.reader = pageReader;
            this.builder = new PageBuilder(Exec.getBufferAllocator(), outputSchema, pageOutput);
            this.task = task;
            this.inputSchema = inputSchema;
            this.keyValuePair = keyValuePair;
        }

        @Override
        public void add(Page page) {
            reader.setPage(page);
            List<ColumnConfig> columnConfigList = new ArrayList<>();
            for (ColumnConfig columnConfig : task.getNewColumns().getColumns()) {
                columnConfigList.add(columnConfig);
            }

            while (reader.nextRecord()) {

                int colNum = 0;
                List<String> inputColumns = task.getMappingFrom();
                List<String> searchingKeyData = new ArrayList<>();
                Map<String, Integer> keyMap = new HashMap<>();
                keyMap.put("Key", 0);

                for (Column column : inputSchema.getColumns()) {
                    if (reader.isNull(column)) {
                        if (column.getName().equalsIgnoreCase(inputColumns.get(keyMap.get("Key")))) {
                            searchingKeyData.add("");
                            int key = keyMap.get("Key");
                            keyMap.put("Key", ++key);
                        }
                        builder.setNull(colNum++);
                    } else {
                        add_builder(colNum++, column, searchingKeyData, inputColumns, keyMap);
                    }
                }

                String key = "";
                for (int k = 0; k < searchingKeyData.size(); k++) {
                    key += searchingKeyData.get(k);
                    if (k != searchingKeyData.size() - 1) {
                        key += ",";
                    }
                }

                List<String> matchedData = new ArrayList<>();
                if (keyValuePair.containsKey(key)) {
                    matchedData = keyValuePair.get(key);
                }

                if (matchedData.size() == 0) {
                    for (int k = 0; k < columnConfigList.size(); k++) {
                        add_builder_for_new_column(colNum, columnConfigList.get(k).getType().getName(), "", false);
                        colNum++;
                    }
                } else {
                    for (int k = 0; k < columnConfigList.size(); k++) {
                        add_builder_for_new_column(colNum, columnConfigList.get(k).getType().getName(), matchedData.get(k), true);
                        colNum++;
                    }
                }
                builder.addRecord();
            }

        }


        @Override
        public void finish() {
            builder.finish();
        }

        @Override
        public void close() {
            builder.close();
        }

        private void add_builder(int colNum, Column column, List<String> searchingKeyData, List<String> inputColumns, Map<String, Integer> keyMap) {
            if (Types.STRING.equals(column.getType())) {
                if (keyMap.get("Key") < inputColumns.size()) {
                    if (column.getName().equalsIgnoreCase(inputColumns.get(keyMap.get("Key")))) {
                        searchingKeyData.add(reader.getString(column));
                        int key = keyMap.get("Key");
                        keyMap.put("Key", ++key);
                    }
                }
                builder.setString(colNum, reader.getString(column));
            } else if (Types.BOOLEAN.equals(column.getType())) {
                if (keyMap.get("Key") < inputColumns.size()) {
                    if (column.getName().equalsIgnoreCase(inputColumns.get(keyMap.get("Key")))) {
                        searchingKeyData.add(String.valueOf(reader.getBoolean(column)));
                        int key = keyMap.get("Key");
                        keyMap.put("Key", ++key);
                    }
                }
                builder.setBoolean(colNum, reader.getBoolean(column));
            } else if (Types.DOUBLE.equals(column.getType())) {
                if (keyMap.get("Key") < inputColumns.size()) {
                    if (column.getName().equalsIgnoreCase(inputColumns.get(keyMap.get("Key")))) {
                        searchingKeyData.add(String.valueOf(reader.getDouble(column)));
                        int key = keyMap.get("Key");
                        keyMap.put("Key", ++key);
                    }
                }
                builder.setDouble(colNum, reader.getDouble(column));
            } else if (Types.LONG.equals(column.getType())) {
                if (keyMap.get("Key") < inputColumns.size()) {
                    if (column.getName().equalsIgnoreCase(inputColumns.get(keyMap.get("Key")))) {
                        searchingKeyData.add(String.valueOf(reader.getLong(column)));
                        int key = keyMap.get("Key");
                        keyMap.put("Key", ++key);
                    }
                }

                builder.setLong(colNum, reader.getLong(column));
            } else if (Types.TIMESTAMP.equals(column.getType())) {
                if (keyMap.get("Key") < inputColumns.size()) {
                    if (column.getName().equalsIgnoreCase(inputColumns.get(keyMap.get("Key")))) {
                        searchingKeyData.add(String.valueOf(reader.getTimestamp(column)));
                        int key = keyMap.get("Key");
                        keyMap.put("Key", ++key);
                    }
                }
                builder.setTimestamp(colNum, reader.getTimestamp(column));
            }
        }

        private void add_builder_for_new_column(int colNum, String newlyAddedColumnType, String matchedData, Boolean isDataMatched) {
            try {
                if (newlyAddedColumnType.equalsIgnoreCase("string")) {
                    if (isDataMatched) {
                        builder.setString(colNum, matchedData);
                    } else {
                        builder.setString(colNum, "");
                    }

                } else if (newlyAddedColumnType.equalsIgnoreCase("long")) {
                    if (isDataMatched) {
                        if (matchedData.length() == 0) {
                            builder.setLong(colNum, 0);
                        } else {
                            builder.setLong(colNum, Long.parseLong(matchedData));
                        }
                    } else {
                        builder.setLong(colNum, 0);
                    }

                } else if (newlyAddedColumnType.equalsIgnoreCase("double")) {
                    if (isDataMatched) {
                        if (matchedData.length() == 0) {
                            builder.setDouble(colNum, 0.0);
                        } else {
                            builder.setDouble(colNum, Double.parseDouble(matchedData));
                        }
                    } else {
                        builder.setDouble(colNum, 0.0);
                    }
                } else if (newlyAddedColumnType.equalsIgnoreCase("boolean")) {
                    if (isDataMatched) {
                        if (matchedData.length() == 0) {
                            builder.setNull(colNum);
                        } else {
                            builder.setBoolean(colNum, Boolean.parseBoolean(matchedData));
                        }
                    } else {
                        builder.setNull(colNum);
                    }
                } else if (newlyAddedColumnType.equalsIgnoreCase("timestamp")) {
                    if (isDataMatched) {
                        if (matchedData.length() == 0) {
                            builder.setNull(colNum);
                        } else {
                            java.sql.Timestamp timestamp = java.sql.Timestamp.valueOf(matchedData);
                            Instant instant = timestamp.toInstant();
                            Timestamp spiTimeStamp = Timestamp.ofInstant(instant);
                            builder.setTimestamp(colNum, spiTimeStamp);
                        }
                    } else {
                        builder.setNull(colNum);
                    }

                }
            } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException("Data type could not be cast due to wrong data or issue in typecasting timestamp", e);
            }

        }
    }
}
