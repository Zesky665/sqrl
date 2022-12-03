package com.datasqrl.physical.database.relational;

import com.datasqrl.config.constraints.OptionalMinString;
import com.datasqrl.config.error.ErrorCollector;
import com.datasqrl.physical.database.relational.metadata.JDBCMetadataStore;
import com.datasqrl.metadata.MetadataStore;
import com.datasqrl.config.provider.Dialect;
import com.datasqrl.config.provider.JDBCConnectionProvider;
import com.datasqrl.metadata.MetadataStoreProvider;
import com.datasqrl.config.serializer.KryoProvider;
import com.datasqrl.config.serializer.SerializerProvider;
import com.datasqrl.config.util.ConfigurationUtil;
import com.datasqrl.physical.database.DatabaseEngineConfiguration;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Strings;
import lombok.*;

import javax.validation.constraints.NotNull;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.regex.Pattern;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JDBCEngineConfiguration implements DatabaseEngineConfiguration {

  public static final String ENGINE_NAME = "jdbc";

  String host;
  int port;
  @NonNull @NotNull
  String dbURL;
  String user;
  String password;
  @OptionalMinString
  String driverName;
  @NonNull @NotNull
  Dialect dialect;
  @NonNull @NotNull
  String database;

  public static Pattern validDBName = Pattern.compile("^[a-z][_a-z0-9$]{2,}$");

  @Override
  public String getEngineName() {
    return ENGINE_NAME;
  }

  private boolean validate(@NonNull ErrorCollector errors) {
    ConfigurationUtil.javaxValidate(this,errors);
    if (Strings.isNullOrEmpty(database) || !validDBName.matcher(database).matches()) {
      errors.fatal("Invalid database name: %s", database);
      return false;
    }
    return true;
  }

  @Override
  public JDBCEngine initialize(@NonNull ErrorCollector errors) {
    if (validate(errors)) return new JDBCEngine(this);
    else return null;
  }

  @JsonIgnore
  public ConnectionProvider getConnectionProvider() {
    //Construct URL pointing at database
    String url = dbURL;
    switch (dialect) {
      case H2:
      case MYSQL:
      case POSTGRES:
        if (!url.endsWith("/")) {
          url += "/";
        }
        url += database;
        break;
      default:
        throw new UnsupportedOperationException("Unsupported dialect: " + dialect);
    }

    //Modify url for database engine
    if (dialect.equals(Dialect.H2)) {
      url += ";database_to_upper=false";
    }

    return new ConnectionProvider(host, port, url, user,
            password, driverName, dialect, database);
  }

  @Override
  @JsonIgnore
  public MetadataStoreProvider getMetadataStore() {
    return new StoreProvider(getConnectionProvider());
  }

  @Getter
  @AllArgsConstructor
  @EqualsAndHashCode
  @ToString
  public static class ConnectionProvider implements JDBCConnectionProvider {

    private String host;
    private int port;
    @NonNull
    private String dbURL;
    private String user;
    private String password;
    private String driverName;
    private Dialect dialect;
    private String databaseName;

    @JsonIgnore
    @Override
    public Connection getConnection() throws SQLException, ClassNotFoundException {
      return DriverManager.getConnection(dbURL, user, password);
    }
  }

  @Value
  public static class StoreProvider implements MetadataStoreProvider {

    ConnectionProvider connection;
    SerializerProvider serializer = new KryoProvider(); //TODO: make configurable

    @Override
    public MetadataStore openStore() {
      return new JDBCMetadataStore(connection, serializer.getSerializer());
    }

  }

}