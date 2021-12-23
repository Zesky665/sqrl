package ai.dataeng.sqml.analyzer2;

import ai.dataeng.execution.criteria.Criteria;
import ai.dataeng.execution.criteria.EqualsCriteria;
import ai.dataeng.execution.table.H2Table;
import ai.dataeng.execution.table.column.BooleanColumn;
import ai.dataeng.execution.table.column.Columns;
import ai.dataeng.execution.table.column.FloatColumn;
import ai.dataeng.execution.table.column.H2Column;
import ai.dataeng.execution.table.column.IntegerColumn;
import ai.dataeng.execution.table.column.StringColumn;
import ai.dataeng.sqml.tree.name.Name;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import org.apache.flink.connector.jdbc.JdbcConnectionOptions;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.api.internal.StatementSetImpl;
import org.apache.flink.table.catalog.Column;
import org.apache.flink.table.types.logical.ArrayType;
import org.apache.flink.table.types.logical.BigIntType;
import org.apache.flink.table.types.logical.BinaryType;
import org.apache.flink.table.types.logical.BooleanType;
import org.apache.flink.table.types.logical.CharType;
import org.apache.flink.table.types.logical.DateType;
import org.apache.flink.table.types.logical.DayTimeIntervalType;
import org.apache.flink.table.types.logical.DecimalType;
import org.apache.flink.table.types.logical.DistinctType;
import org.apache.flink.table.types.logical.DoubleType;
import org.apache.flink.table.types.logical.FloatType;
import org.apache.flink.table.types.logical.IntType;
import org.apache.flink.table.types.logical.LocalZonedTimestampType;
import org.apache.flink.table.types.logical.LogicalType;
import org.apache.flink.table.types.logical.LogicalTypeVisitor;
import org.apache.flink.table.types.logical.MapType;
import org.apache.flink.table.types.logical.MultisetType;
import org.apache.flink.table.types.logical.NullType;
import org.apache.flink.table.types.logical.RawType;
import org.apache.flink.table.types.logical.RowType;
import org.apache.flink.table.types.logical.SmallIntType;
import org.apache.flink.table.types.logical.StructuredType;
import org.apache.flink.table.types.logical.SymbolType;
import org.apache.flink.table.types.logical.TimeType;
import org.apache.flink.table.types.logical.TimestampType;
import org.apache.flink.table.types.logical.TinyIntType;
import org.apache.flink.table.types.logical.VarBinaryType;
import org.apache.flink.table.types.logical.VarCharType;
import org.apache.flink.table.types.logical.YearMonthIntervalType;
import org.apache.flink.table.types.logical.ZonedTimestampType;

@AllArgsConstructor
public class SqrlSinkBuilder {

  private final TableEnvironment env;
  private final String jdbcUrl;

  @SneakyThrows
  public Map<String, H2Table> build(SqrlCatalogManager catalogManager, boolean execute) {
//    System.out.println(jdbcUrl);
//    Map<String, H2Table> tableMap = new HashMap<>();
//
//    JdbcConnectionOptions jdbcOptions = new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
//        .withUrl(jdbcUrl)
//        .withUsername("test")
//        .withPassword("test")
//        .build();
//    Connection conn = getConnection(jdbcOptions);
//
//    StatementSetImpl set = (StatementSetImpl) env.createStatementSet();
//
//    for (SqrlTable entity : catalogManager.getCurrentTables()) {
//      String sqlFriendlyName = entity.getNamePath().toString().replaceAll("\\.", "_");
//      String tableName = sqlFriendlyName + "_flink";
//
////      String tableName = matTable.getFlinkName();
//      String sql = "CREATE TABLE %s("
//          + entity.getTable().getResolvedSchema()
//          .getColumns()
//          .stream()
//          .map(c -> "`" + c.getName() + "`" + " " + getType(c.getDataType().getLogicalType()))
//          .collect(Collectors.joining(", "))
//          + getPK(entity)
//          + ") WITH ( "
//          + "'connector' = 'jdbc',"
//          + "'url'='"+jdbcUrl+"',"
//          + "'username'='test',"
//          + "'password'='test',"
//          + "'table-name' = '%s'"
//          + ")";
//
//      H2Table table = convertToH2Table(entity, tableName, entity.getNamePath().getLength() != 1);
//      tableMap.put(entity.getNamePath().getLast().getDisplay(), table);
//
//      System.out.println(String.format(sql, tableName, tableName));
//      env.executeSql(
//          String.format(sql, tableName, tableName)
//      );
//
////      String drop = "DROP TABLE IF EXISTS %s;";
//      String postgresSql = "CREATE TABLE %s(" +
//          entity.getTable().getResolvedSchema()
//              .getColumns()
//              .stream().map(
//                  c -> "\"" + c.getName() + "\"" + " " + getSqlType(c.getDataType().getLogicalType()))
//              .collect(Collectors.joining(", "))
//          + getPostgresPK(entity)
//          + ")";
//
//      if (execute) {
////        conn.createStatement().execute(String.format(drop, tableName));
//        conn.createStatement().execute(String.format(postgresSql, tableName));
//      }
//
//      set.addInsert(tableName, entity.getTable());
//    }
//
//    if (execute) {
////      for (MaterializeTable view : tableManager.getViews()) {
////        System.out.println(view.getQuery());
////        conn.createStatement().execute(view.getQuery());
////        //Todo: Fix copy paste
////        H2Table table = convertToH2Table(view.getEntity(), view.getViewName(), view.getEntity().getNamePath().getLength() != 1);
////        tableMap.put(view.getEntity().getNamePath().getLast().getDisplay(), table);
////      }
//
//      //Can throw an exception if batch
//      System.out.println(set.explain());
//      TableResult result = set.execute();
//      System.out.println(result);
//
//      try {
//        result.await();
//      } catch (InterruptedException e) {
//        e.printStackTrace();
//      } catch (ExecutionException e) {
//        e.printStackTrace();
//      }
//    }
//    return tableMap;
    return null;
  }

  private H2Table convertToH2Table(SqrlTable entity, String display, boolean isnested) {

    H2Table table = new H2Table(new Columns(
        convertColumns(entity)
    ), display, isnested ? convertPk(entity) : Optional.empty());

    return table;
  }

  private Optional<Criteria> convertPk(SqrlTable entity) {

    if (!entity.getContextKeyWoPk().isEmpty()) {
      return Optional.of(new EqualsCriteria(entity.getContextKeyWoPk().get(0).getCanonical(), entity.getContextKeyWoPk().get(0).getCanonical()));
    }

    return Optional.empty();
  }

  private List<H2Column> convertColumns(SqrlTable entity) {
    List<H2Column> columns = new ArrayList<>();
    for (Column column : entity.getTable().getResolvedSchema().getColumns()) {
      H2TableConverter conv = new H2TableConverter(column.getName());
      columns.add(column.getDataType().getLogicalType().accept(conv));
    }

    return columns;
  }

  private String getPK(SqrlTable entity1) {
    if (!entity1.getPrimaryKey().isEmpty()) {

      return ", PRIMARY KEY ("+
          entity1.getPrimaryKey().stream()
              .map(Name::getCanonical)
              .collect(Collectors.joining(", "))
          +") NOT ENFORCED";
    }
    return "";
  }
  private String getPostgresPK(SqrlTable entity1) {
    if (!entity1.getPrimaryKey().isEmpty()) {

      return ", PRIMARY KEY ("+
          entity1.getPrimaryKey().stream()
              .map(Name::getCanonical)
              .collect(Collectors.joining(", "))
          +")";
    }
    return "";
  }

  public static Connection getConnection(JdbcConnectionOptions jdbcOptions) throws SQLException, ClassNotFoundException {
    return DriverManager.getConnection(jdbcOptions.getDbURL(),
        jdbcOptions.getUsername().orElse(null),
        jdbcOptions.getPassword().orElse(null));

  }

  private String getType(LogicalType logicalType) {

    return logicalType.accept(new TypeConverter());
  }


  public class H2TableConverter implements LogicalTypeVisitor<H2Column> {

    private final String name;

    public H2TableConverter(String name) {
      this.name = name;
    }
    @Override
    public H2Column visit(CharType charType) {
      return new StringColumn(name, name);
    }

    @Override
    public H2Column visit(VarCharType varCharType) {
      return new StringColumn(name, name);
    }

    @Override
    public H2Column visit(BooleanType booleanType) {
      return new BooleanColumn(name, name);
    }

    @Override
    public H2Column visit(BinaryType binaryType) {
      return null;
    }

    @Override
    public H2Column visit(VarBinaryType varBinaryType) {
      return null;
    }

    @Override
    public H2Column visit(DecimalType decimalType) {
      return new FloatColumn(name, name);
    }

    @Override
    public H2Column visit(TinyIntType tinyIntType) {
      return new IntegerColumn(name, name);
    }

    @Override
    public H2Column visit(SmallIntType smallIntType) {
      return new IntegerColumn(name, name);
    }

    @Override
    public H2Column visit(IntType intType) {
      return new IntegerColumn(name, name);
    }

    @Override
    public H2Column visit(BigIntType bigIntType) {
      return new IntegerColumn(name, name);
    }

    @Override
    public H2Column visit(FloatType floatType) {
      return new FloatColumn(name, name);
    }

    @Override
    public H2Column visit(DoubleType doubleType) {
      return new FloatColumn(name, name);
    }

    @Override
    public H2Column visit(DateType dateType) {
      return null;
    }

    @Override
    public H2Column visit(TimeType timeType) {
      return null;
    }

    @Override
    public H2Column visit(TimestampType timestampType) {
      return null;
    }

    @Override
    public H2Column visit(ZonedTimestampType zonedTimestampType) {
      return null;
    }

    @Override
    public H2Column visit(LocalZonedTimestampType localZonedTimestampType) {
      return null;
    }

    @Override
    public H2Column visit(YearMonthIntervalType yearMonthIntervalType) {
      return null;
    }

    @Override
    public H2Column visit(DayTimeIntervalType dayTimeIntervalType) {
      return null;
    }

    @Override
    public H2Column visit(ArrayType arrayType) {
      return null;
    }

    @Override
    public H2Column visit(MultisetType multisetType) {
      return null;
    }

    @Override
    public H2Column visit(MapType mapType) {
      return null;
    }

    @Override
    public H2Column visit(RowType rowType) {
      return null;
    }

    @Override
    public H2Column visit(DistinctType distinctType) {
      return null;
    }

    @Override
    public H2Column visit(StructuredType structuredType) {
      return null;
    }

    @Override
    public H2Column visit(NullType nullType) {
      return null;
    }

    @Override
    public H2Column visit(RawType<?> rawType) {
      return null;
    }

    @Override
    public H2Column visit(SymbolType<?> symbolType) {
      return null;
    }

    @Override
    public H2Column visit(LogicalType logicalType) {
      return null;
    }
  }

    public class TypeConverter implements LogicalTypeVisitor<String> {

    @Override
    public String visit(CharType charType) {
      return "String";
    }

    @Override
    public String visit(VarCharType varCharType) {
      return "String";
    }

    @Override
    public String visit(BooleanType booleanType) {
      return "BOOLEAN";
    }

    @Override
    public String visit(BinaryType binaryType) {
      return "BYTES";
    }

    @Override
    public String visit(VarBinaryType varBinaryType) {
      return "BYTES";
    }

    @Override
    public String visit(DecimalType decimalType) {
      return "DECIMAL";
    }

    @Override
    public String visit(TinyIntType tinyIntType) {
      return "INT";
    }

    @Override
    public String visit(SmallIntType smallIntType) {
      return "INT";
    }

    @Override
    public String visit(IntType intType) {
      return "INT";
    }

    @Override
    public String visit(BigIntType bigIntType) {
      return "BIGINT";
    }

    @Override
    public String visit(FloatType floatType) {
      return "FLOAT";
    }

    @Override
    public String visit(DoubleType doubleType) {
      return "DOUBLE";
    }

    @Override
    public String visit(DateType dateType) {
      return "TIMESTAMP";
    }

    @Override
    public String visit(TimeType timeType) {
      return "TIME";
    }

    @Override
    public String visit(TimestampType timestampType) {
      return "TIMESTAMP";
    }

    @Override
    public String visit(ZonedTimestampType zonedTimestampType) {
      return "TIMESTAMP";
    }

    @Override
    public String visit(LocalZonedTimestampType localZonedTimestampType) {
      return "TIMESTAMP";
    }

    @Override
    public String visit(YearMonthIntervalType yearMonthIntervalType) {
      return "String";
    }

    @Override
    public String visit(DayTimeIntervalType dayTimeIntervalType) {
      return "String";
    }

    @Override
    public String visit(ArrayType arrayType) {
      return null;
    }

    @Override
    public String visit(MultisetType multisetType) {
      return null;
    }

    @Override
    public String visit(MapType mapType) {
      return null;
    }

    @Override
    public String visit(RowType rowType) {
      return null;
    }

    @Override
    public String visit(DistinctType distinctType) {
      return null;
    }

    @Override
    public String visit(StructuredType structuredType) {
      return null;
    }

    @Override
    public String visit(NullType nullType) {
      return null;
    }

    @Override
    public String visit(RawType<?> rawType) {
      return null;
    }

    @Override
    public String visit(SymbolType<?> symbolType) {
      return null;
    }

    @Override
    public String visit(LogicalType logicalType) {
      return null;
    }
  }
  private String getSqlType(LogicalType logicalType) {

    return logicalType.accept(new SqlTypeConverter());
  }

  public class SqlTypeConverter implements LogicalTypeVisitor<String> {

    @Override
    public String visit(CharType charType) {
      return "VARCHAR";
    }

    @Override
    public String visit(VarCharType varCharType) {
      return "VARCHAR";
    }

    @Override
    public String visit(BooleanType booleanType) {
      return "BOOLEAN";
    }

    @Override
    public String visit(BinaryType binaryType) {
      return "BYTES";
    }

    @Override
    public String visit(VarBinaryType varBinaryType) {
      return "BYTES";
    }

    @Override
    public String visit(DecimalType decimalType) {
      return "DECIMAL";
    }

    @Override
    public String visit(TinyIntType tinyIntType) {
      return "INT";
    }

    @Override
    public String visit(SmallIntType smallIntType) {
      return "INT";
    }

    @Override
    public String visit(IntType intType) {
      return "INT";
    }

    @Override
    public String visit(BigIntType bigIntType) {
      return "BIGINT";
    }

    @Override
    public String visit(FloatType floatType) {
      return "FLOAT";
    }

    @Override
    public String visit(DoubleType doubleType) {
      return "DECIMAL";
    }

    @Override
    public String visit(DateType dateType) {
      return "TIMESTAMP";
    }

    @Override
    public String visit(TimeType timeType) {
      return "TIME";
    }

    @Override
    public String visit(TimestampType timestampType) {
      return "TIMESTAMP";
    }

    @Override
    public String visit(ZonedTimestampType zonedTimestampType) {
      return "TIMESTAMP";
    }

    @Override
    public String visit(LocalZonedTimestampType localZonedTimestampType) {
      return "TIMESTAMP";
    }

    @Override
    public String visit(YearMonthIntervalType yearMonthIntervalType) {
      return "VARCHAR";
    }

    @Override
    public String visit(DayTimeIntervalType dayTimeIntervalType) {
      return "VARCHAR";
    }

    @Override
    public String visit(ArrayType arrayType) {
      return null;
    }

    @Override
    public String visit(MultisetType multisetType) {
      return null;
    }

    @Override
    public String visit(MapType mapType) {
      return null;
    }

    @Override
    public String visit(RowType rowType) {
      return null;
    }

    @Override
    public String visit(DistinctType distinctType) {
      return null;
    }

    @Override
    public String visit(StructuredType structuredType) {
      return null;
    }

    @Override
    public String visit(NullType nullType) {
      return null;
    }

    @Override
    public String visit(RawType<?> rawType) {
      return null;
    }

    @Override
    public String visit(SymbolType<?> symbolType) {
      return null;
    }

    @Override
    public String visit(LogicalType logicalType) {
      return null;
    }
  }
}
