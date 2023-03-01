package org.embulk.filter.mysql_lookup;


import org.embulk.config.ConfigException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileFilter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public class DatabaseConnection {
    private static Connection connection=null;

    private DatabaseConnection(MysqlLookupFilterPlugin.PluginTask task) throws Exception {
        try{
            if(task.getDriverClass().isPresent()){
                this.loadMySqlJdbcDriver(task.getDriverClass().get(),task.getDriverPath());
            }else{
                this.loadMySqlJdbcDriver("com.mysql.cj.jdbc.Driver",task.getDriverPath());
            }
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

    private Class<? extends java.sql.Driver> loadMySqlJdbcDriver(
            final String className,
            final Optional<String> driverPath)
    {
        synchronized (mysqlJdbcDriver) {
            if (mysqlJdbcDriver.get() != null) {
                return mysqlJdbcDriver.get();
            }

            if (driverPath.isPresent()) {
                logger.info(
                        "\"driver_path\" is set to load the MySQL JDBC driver class \"{}\". Adding it to classpath.", className);
                this.addDriverJarToClasspath(driverPath.get());
            }
            try {
                // If the class is found from the ClassLoader of the plugin, that is prioritized the highest.
                final Class<? extends java.sql.Driver> found = loadJdbcDriverClassForName(className);
                mysqlJdbcDriver.compareAndSet(null, found);

                if (driverPath.isPresent()) {
                    logger.warn(
                            "\"driver_path\" is set while the MySQL JDBC driver class \"{}\" is found from the PluginClassLoader."
                                    + " \"driver_path\" is ignored.", className);
                }
                return found;
            }
            catch (final ClassNotFoundException ex) {
                //throw new ConfigException("The MySQL JDBC driver for the class \"" + className + "\" is not found.", ex);
            }
            final File root = this.findPluginRoot();
            final File driverLib = new File(root, "default_jdbc_driver");
            final File[] files = driverLib.listFiles(new FileFilter() {
                @Override
                public boolean accept(final File file)
                {
                    return file.isFile() && file.getName().endsWith(".jar");
                }
            });
            if (files == null || files.length == 0) {
                throw new ConfigException(new ClassNotFoundException(
                        "The MySQL JDBC driver for the class \"" + className + "\" is not found"
                                + " in \"default_jdbc_driver\" (" + root.getAbsolutePath() + ")."));
            }
            for (final File file : files) {
                logger.info(
                        "The MySQL JDBC driver for the class \"{}\" is expected to be found"
                                + " in \"default_jdbc_driver\" at {}.", className, file.getAbsolutePath());
                this.addDriverJarToClasspath(file.getAbsolutePath());
            }

            try {
                // If the class is found from the ClassLoader of the plugin, that is prioritized the highest.
                final Class<? extends java.sql.Driver> found = loadJdbcDriverClassForName(className);
                mysqlJdbcDriver.compareAndSet(null, found);

                if (driverPath.isPresent()) {
                    logger.warn(
                            "\"driver_path\" is set while the MySQL JDBC driver class \"{}\" is found from the PluginClassLoader."
                                    + " \"driver_path\" is ignored.", className);
                }
                return found;
            }
            catch (final ClassNotFoundException ex) {
                throw new ConfigException("The MySQL JDBC driver for the class \"" + className + "\" is not found.", ex);
            }

        }
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends java.sql.Driver> loadJdbcDriverClassForName(final String className) throws ClassNotFoundException
    {
        return (Class<? extends java.sql.Driver>) Class.forName(className);
    }

    private static final AtomicReference<Class<? extends java.sql.Driver>> mysqlJdbcDriver = new AtomicReference<>();

    private static final Logger logger = LoggerFactory.getLogger(DatabaseConnection.class);

    protected void addDriverJarToClasspath(String glob)
    {
        // TODO match glob
        final ClassLoader loader = getClass().getClassLoader();
        if (!(loader instanceof URLClassLoader)) {
            throw new RuntimeException("Plugin is not loaded by URLClassLoader unexpectedly.");
        }
        if (!"org.embulk.plugin.PluginClassLoader".equals(loader.getClass().getName())) {
            throw new RuntimeException("Plugin is not loaded by PluginClassLoader unexpectedly.");
        }
        Path path = Paths.get(glob);
        if (!path.toFile().exists()) {
            throw new ConfigException("The specified driver jar doesn't exist: " + glob);
        }
        final Method addPathMethod;
        try {
            addPathMethod = loader.getClass().getMethod("addPath", Path.class);
        } catch (final NoSuchMethodException ex) {
            throw new RuntimeException("Plugin is not loaded a ClassLoader which has addPath(Path), unexpectedly.");
        }
        try {
            addPathMethod.invoke(loader, Paths.get(glob));
        } catch (final IllegalAccessException ex) {
            throw new RuntimeException(ex);
        } catch (final InvocationTargetException ex) {
            final Throwable targetException = ex.getTargetException();
            if (targetException instanceof MalformedURLException) {
                throw new IllegalArgumentException(targetException);
            } else if (targetException instanceof RuntimeException) {
                throw (RuntimeException) targetException;
            } else {
                throw new RuntimeException(targetException);
            }
        }
    }

    protected File findPluginRoot()
    {
        try {
            URL url = getClass().getResource("/" + getClass().getName().replace('.', '/') + ".class");
            if (url.toString().startsWith("jar:")) {
                url = new URL(url.toString().replaceAll("^jar:", "").replaceAll("![^!]*$", ""));
            }

            File folder = new File(url.toURI()).getParentFile();
            for (;; folder = folder.getParentFile()) {
                if (folder == null) {
                    throw new RuntimeException("Cannot find 'embulk-filter-xxx' folder.");
                }

                if (folder.getName().startsWith("embulk-input-")) {
                    return folder;
                }
            }
        } catch (MalformedURLException | URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }


}
