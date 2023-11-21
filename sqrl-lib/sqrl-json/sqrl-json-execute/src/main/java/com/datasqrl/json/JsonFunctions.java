package com.datasqrl.json;

import com.datasqrl.function.SqrlFunction;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.ReadContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.SneakyThrows;
import lombok.Value;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.table.annotation.DataTypeHint;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.catalog.DataTypeFactory;
import org.apache.flink.table.functions.AggregateFunction;
import org.apache.flink.table.functions.ScalarFunction;
import org.apache.flink.table.runtime.functions.SqlJsonUtils;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.inference.ArgumentTypeStrategy;
import org.apache.flink.table.types.inference.InputTypeStrategies;
import org.apache.flink.table.types.inference.InputTypeStrategy;
import org.apache.flink.table.types.inference.TypeInference;
import org.apache.flink.table.types.inference.TypeStrategies;
import org.apache.flink.table.types.inference.strategies.AndArgumentTypeStrategy;
import org.apache.flink.table.types.inference.strategies.SpecificInputTypeStrategies;
import org.apache.flink.table.types.logical.LogicalTypeFamily;

public class JsonFunctions {

  public static final ToJson TO_JSON = new ToJson();
  public static final JsonToString JSON_TO_STRING = new JsonToString();
  public static final JsonObject JSON_OBJECT = new JsonObject();
  public static final JsonArray JSON_ARRAY = new JsonArray();
  public static final JsonExtract JSON_EXTRACT = new JsonExtract();
  public static final JsonQuery JSON_QUERY = new JsonQuery();
  public static final JsonExists JSON_EXISTS = new JsonExists();
  public static final JsonArrayAgg JSON_ARRAYAGG = new JsonArrayAgg();
  public static final JsonObjectAgg JSON_OBJECTAGG = new JsonObjectAgg();

  public static final ObjectMapper mapper = new ObjectMapper();

  public static ArgumentTypeStrategy createJsonArgumentTypeStrategy(DataTypeFactory typeFactory) {
    return InputTypeStrategies.or(SpecificInputTypeStrategies.JSON_ARGUMENT,
        InputTypeStrategies.explicit(createJsonType(typeFactory)));
  }

  public static DataType createJsonType(DataTypeFactory typeFactory) {
    DataType dataType = DataTypes.of(FlinkJsonType.class).toDataType(typeFactory);
    return dataType;
  }

  public static class ToJson extends ScalarFunction implements SqrlFunction {

    public FlinkJsonType eval(String json) {
      try {
        return new FlinkJsonType(mapper.readTree(json).toString());
      } catch (JsonProcessingException e) {
        return null;
      }
    }

    @Override
    public String getDocumentation() {
      return "Converts string to json";
    }
  }

  public static class JsonToString extends ScalarFunction implements SqrlFunction {

    public String eval(FlinkJsonType json) {
      if (json == null) {
        return null;
      }
      return json.getJson();
    }

    @Override
    public String getDocumentation() {
      return "Converts json to string";
    }
  }


  public static class JsonObject extends ScalarFunction implements SqrlFunction {

    @SneakyThrows
    public FlinkJsonType eval(Object... objects) {
      if (objects.length % 2 != 0) {
        throw new IllegalArgumentException("Arguments should be in key-value pairs");
      }

      ObjectMapper mapper = new ObjectMapper();
      ObjectNode objectNode = mapper.createObjectNode();

      for (int i = 0; i < objects.length; i += 2) {
        if (!(objects[i] instanceof String)) {
          throw new IllegalArgumentException("Key must be a string");
        }
        String key = (String) objects[i];
        Object value = objects[i + 1];
        if (value instanceof FlinkJsonType) {
          FlinkJsonType type = (FlinkJsonType) value;
          objectNode.putIfAbsent(key, mapper.readTree(type.json));
        } else {
          objectNode.putPOJO(key, value);
        }
      }

      return new FlinkJsonType(objectNode.toString());
    }


    @Override
    public TypeInference getTypeInference(DataTypeFactory typeFactory) {
      AndArgumentTypeStrategy and = InputTypeStrategies
          .and(InputTypeStrategies.logical(LogicalTypeFamily.CHARACTER_STRING),
              InputTypeStrategies.LITERAL);
      InputTypeStrategy inputTypeStrategy1 = InputTypeStrategies.repeatingSequence(
          and,
          createJsonArgumentTypeStrategy(typeFactory));
      InputTypeStrategy inputTypeStrategy = InputTypeStrategies
          .compositeSequence()
          .finishWithVarying(inputTypeStrategy1);

      return TypeInference.newBuilder()
          .inputTypeStrategy(inputTypeStrategy)
          .outputTypeStrategy(
              TypeStrategies.explicit(DataTypes.of(FlinkJsonType.class).toDataType(typeFactory)))
          .build();
    }

    @Override
    public String getDocumentation() {
      return "This function creates a JSON object from key-value pairs";
    }
  }

  public static class JsonArray extends ScalarFunction implements SqrlFunction {

    @SneakyThrows
    public FlinkJsonType eval(Object... objects) {
      ObjectMapper mapper = new ObjectMapper();
      ArrayNode arrayNode = mapper.createArrayNode();

      for (Object value : objects) {
        if (value instanceof FlinkJsonType) {
          FlinkJsonType type = (FlinkJsonType) value;
          arrayNode.add(mapper.readTree(type.json));
        } else {
          arrayNode.addPOJO(value); // putPOJO to handle arbitrary objects
        }
      }

      return new FlinkJsonType(arrayNode.toString());
    }

    @Override
    public TypeInference getTypeInference(DataTypeFactory typeFactory) {
      InputTypeStrategy inputTypeStrategy = InputTypeStrategies.varyingSequence(
          createJsonArgumentTypeStrategy(typeFactory));

      return TypeInference.newBuilder()
          .inputTypeStrategy(inputTypeStrategy)
          .outputTypeStrategy(TypeStrategies.explicit(createJsonType(typeFactory)))
          .build();
    }

    @Override
    public String getDocumentation() {
      return "This function creates a JSON object from key-value pairs";
    }
  }

  public static class JsonExtract extends ScalarFunction implements SqrlFunction {

    public String eval(FlinkJsonType input, String pathSpec) {
      try {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode jsonNode = mapper.readTree(input.getJson());
        ReadContext ctx = JsonPath.parse(jsonNode.toString());
        return ctx.read(pathSpec);
      } catch (Exception e) {
        return null;
      }
    }

    public String eval(FlinkJsonType input, String pathSpec, String defaultValue) {
      try {
        ReadContext ctx = JsonPath.parse(input.getJson());
        JsonPath parse = JsonPath.compile(pathSpec);
        return ctx.read(parse, String.class);
      } catch (Exception e) {
        return defaultValue;
      }
    }

    public boolean eval(FlinkJsonType input, String pathSpec, boolean defaultValue) {
      try {
        ReadContext ctx = JsonPath.parse(input.getJson());
        JsonPath parse = JsonPath.compile(pathSpec);
        return ctx.read(parse, Boolean.class);
      } catch (Exception e) {
        return defaultValue;
      }
    }

    public Double eval(FlinkJsonType input, String pathSpec, Double defaultValue) {
      try {
        ReadContext ctx = JsonPath.parse(input.getJson());
        JsonPath parse = JsonPath.compile(pathSpec);
        return ctx.read(parse, Double.class);
      } catch (Exception e) {
        return defaultValue;
      }
    }

    public Integer eval(FlinkJsonType input, String pathSpec, Integer defaultValue) {
      try {
        ReadContext ctx = JsonPath.parse(input.getJson());
        JsonPath parse = JsonPath.compile(pathSpec);
        return ctx.read(parse, Integer.class);
      } catch (Exception e) {
        return defaultValue;
      }
    }

    @Override
    public String getDocumentation() {
      return null;
    }
  }

  public static class JsonQuery extends ScalarFunction implements SqrlFunction {

    public String eval(FlinkJsonType input, String pathSpec) {
      try {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode jsonNode = mapper.readTree(input.getJson());
        ReadContext ctx = JsonPath.parse(jsonNode.toString());
        Object result = ctx.read(pathSpec);
        return mapper.writeValueAsString(result); // Convert the result back to JSON string
      } catch (Exception e) {
        return null;
      }
    }

    @Override
    public String getDocumentation() {
      return null;
    }
  }

  public static class JsonExists extends ScalarFunction implements SqrlFunction {

    public Boolean eval(FlinkJsonType json, String path) {
      try {
        return SqlJsonUtils.jsonExists(json.json, path);
      } catch (Exception e) {
        return false;
      }
    }

    @Override
    public String getDocumentation() {
      return null;
    }
  }

  @Value
  public static class ArrayAgg {

    @DataTypeHint(value = "RAW")
    List<Object> objects;

    public void add(Object value) {
      objects.add(value);
    }
  }

  public static class JsonArrayAgg extends AggregateFunction<FlinkJsonType, ArrayAgg> implements
      SqrlFunction {

    private final ObjectMapper mapper = new ObjectMapper();


    @Override
    public ArrayAgg createAccumulator() {
      return new ArrayAgg(new ArrayList<>());
    }

    public void accumulate(ArrayAgg accumulator, String value) {
      accumulator.add(value);
    }

    @SneakyThrows
    public void accumulate(ArrayAgg accumulator, FlinkJsonType value) {
      accumulator.add(mapper.readTree(value.json));
    }

    public void accumulate(ArrayAgg accumulator, Double value) {
      accumulator.add(value);
    }

    public void accumulate(ArrayAgg accumulator, Long value) {
      accumulator.add(value);
    }

    public void accumulate(ArrayAgg accumulator, Integer value) {
      accumulator.add(value);
    }

    @Override
    public FlinkJsonType getValue(ArrayAgg accumulator) {
      ArrayNode arrayNode = mapper.createArrayNode();
      for (Object o : accumulator.getObjects()) {
        if (o instanceof FlinkJsonType) {
          try {
            arrayNode.add(mapper.readTree(((FlinkJsonType) o).json));
          } catch (JsonProcessingException e) {
            return null;
          }
        } else {
          arrayNode.addPOJO(o);
        }
      }
      return new FlinkJsonType(arrayNode.toString());
    }

    @Override
    public String getDocumentation() {
      return null;
    }
  }

  @Value
  public static class ObjectAgg {

    @DataTypeHint(value = "RAW")
    Map<String, Object> objects;

    public void add(String key, Object value) {
      objects.put(key, value);
    }
  }

  public static class JsonObjectAgg extends
      AggregateFunction<FlinkJsonType, ObjectAgg> implements SqrlFunction {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public ObjectAgg createAccumulator() {
      return new ObjectAgg(new HashMap<>());
    }

    public void accumulate(ObjectAgg accumulator, String key, String value) {
      accumulateObject(accumulator, key, value);
    }

    @SneakyThrows
    public void accumulate(ObjectAgg accumulator, String key, FlinkJsonType value) {
      accumulateObject(accumulator, key, mapper.readTree(value.getJson()));
    }

    public void accumulate(ObjectAgg accumulator, String key, Double value) {
      accumulateObject(accumulator, key, value);
    }

    public void accumulate(ObjectAgg accumulator, String key, Long value) {
      accumulateObject(accumulator, key, value);
    }

    public void accumulate(ObjectAgg accumulator, String key, Integer value) {
      accumulateObject(accumulator, key, value);
    }

    public void accumulateObject(ObjectAgg accumulator, String key, Object value) {
      accumulator.add(key, value);
    }

    @Override
    public FlinkJsonType getValue(ObjectAgg accumulator) {
      ObjectNode objectNode = mapper.createObjectNode();
      accumulator.getObjects().forEach((key, value) -> {
        if (value instanceof FlinkJsonType) {
          try {
            objectNode.set(key, mapper.readTree(((FlinkJsonType) value).json));
          } catch (JsonProcessingException e) {
            // Ignore value
          }
        } else {
          objectNode.putPOJO(key, value);
        }
      });
      return new FlinkJsonType(objectNode.toString());
    }

    @Override
    public String getDocumentation() {
      return "This function aggregates key-value pairs into a JSON object.";
    }


  }
}