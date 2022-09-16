package org.embulk.filter.mysql_lookup;

import java.sql.Connection;
import java.sql.DriverManager;

public class DatabaseConnection {
    private static Connection connection=null;

    private DatabaseConnection(MysqlLookupFilterPlugin.PluginTask task) throws Exception {
        try{
            Class.forName("com.mysql.cj.jdbc.Driver");
            String url = "jdbc:mysql://" + task.getHost() + ":"+task.getPort()+"/"+task.getDatabase();
            connection= DriverManager.getConnection(url, task.getUserName(), task.getPassword());
        }catch (Exception e){
            e.printStackTrace();
            throw new Exception(e);
        }

    }

    public static Connection getConnection(MysqlLookupFilterPlugin.PluginTask task){
        try {
            if(connection==null || connection.isClosed()){
                try {
                    new DatabaseConnection(task);
                    return connection;
                } catch (Exception e) {
                    e.printStackTrace();
                    throw new RuntimeException();
                }
            }
        }catch (Exception e){
            throw new RuntimeException(e);
        }

        return connection;
    }
}
