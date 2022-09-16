Embulk::JavaPlugin.register_filter(
  "mysql_lookup", "org.embulk.filter.mysql_lookup.MysqlLookupFilterPlugin",
  File.expand_path('../../../../classpath', __FILE__))
