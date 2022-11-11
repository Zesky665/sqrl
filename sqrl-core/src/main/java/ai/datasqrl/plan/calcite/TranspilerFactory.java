package ai.datasqrl.plan.calcite;

import static ai.datasqrl.plan.calcite.PlannerFactory.sqlValidatorConfig;

import ai.datasqrl.SqrlCalciteCatalogReader;
import java.util.List;
import java.util.Properties;
import org.apache.calcite.config.CalciteConnectionConfigImpl;
import org.apache.calcite.config.CalciteConnectionProperty;
import org.apache.calcite.jdbc.SqrlCalciteSchema;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.flink.table.planner.calcite.FlinkCalciteSqlValidator;

public class TranspilerFactory {

  public static SqlValidator createSqlValidator(SqrlCalciteSchema schema) {
    Properties p = new Properties();
    p.put(CalciteConnectionProperty.CASE_SENSITIVE.name(), false);


    SqlValidator validator = new FlinkCalciteSqlValidator(
        PlannerFactory.getOperatorTable(),
        new SqrlCalciteCatalogReader(schema, List.of(), PlannerFactory.getTypeFactory(),
            new CalciteConnectionConfigImpl(p).set(CalciteConnectionProperty.CASE_SENSITIVE,
                "false")),
        PlannerFactory.getTypeFactory(),
        sqlValidatorConfig
        );
    return validator;
  }
}
