/*
 * Copyright (c) 2021, DataSQRL. All rights reserved. Use is subject to license terms.
 */
package com.datasqrl.schema.input;

import com.datasqrl.canonicalizer.Name;
import com.datasqrl.canonicalizer.NameCanonicalizer;
import com.datasqrl.io.tables.TableSchema;
import com.datasqrl.model.schema.SchemaDefinition;
import com.datasqrl.schema.constraint.Constraint;
import com.datasqrl.schema.converters.FlexibleSchemaRowMapper;
import com.datasqrl.schema.converters.RowConstructor;
import com.datasqrl.schema.converters.RowMapper;
import com.google.auto.service.AutoService;
import java.util.Collections;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.Setter;
import lombok.ToString;

@Getter
@ToString(callSuper = true)
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@AutoService(TableSchema.class)
public class FlexibleTableSchema extends FlexibleFieldSchema implements TableSchema {

  @Setter
  private SchemaDefinition definition;
  private boolean isPartialSchema;
  @NonNull
  private RelationType<Field> fields;
  @NonNull
  private List<Constraint> constraints;

  public FlexibleTableSchema(Name name, SchemaElementDescription description, Object default_value,
                             boolean isPartialSchema, RelationType<Field> fields, List<Constraint> constraints,
      SchemaDefinition definition) {
    super(name, description, default_value);
    this.isPartialSchema = isPartialSchema;
    this.fields = fields;
    this.constraints = constraints;
    this.definition = definition;
  }

  @Override
  public RowMapper getRowMapper(RowConstructor rowConstructor,
                                boolean hasSourceTimestamp) {
    return new FlexibleSchemaRowMapper(this, hasSourceTimestamp,
            rowConstructor);
  }

  @Override
  public String getSchemaType() {
    return FlexibleTableSchemaFactory.SCHEMA_TYPE;
  }

  @Override
  public SchemaValidator getValidator(SchemaAdjustmentSettings schemaAdjustmentSettings, boolean hasSourceTimestamp) {

    InputTableSchema inputTableSchema = new InputTableSchema(this, hasSourceTimestamp);
    DefaultSchemaValidator validator = new DefaultSchemaValidator(inputTableSchema,
        schemaAdjustmentSettings,
        NameCanonicalizer.SYSTEM,
            new FlexibleTypeMatcher(schemaAdjustmentSettings));
    return validator;
  }

  @Setter
  public static class Builder extends FlexibleFieldSchema.Builder {

    protected boolean isPartialSchema = true;
    protected RelationType<Field> fields;
    protected List<Constraint> constraints = Collections.EMPTY_LIST;
    protected SchemaDefinition definition;

    public void copyFrom(FlexibleTableSchema f) {
      super.copyFrom(f);
      isPartialSchema = f.isPartialSchema;
      fields = f.fields;
      constraints = f.constraints;
      definition = f.definition;
    }

    public FlexibleTableSchema build() {
      return new FlexibleTableSchema(name, description, default_value, isPartialSchema, fields,
              constraints, definition);
    }
  }

  public static FlexibleTableSchema empty(Name name) {
    Builder b = new Builder();
    b.setName(name);
    b.setFields(RelationType.EMPTY);
    return b.build();
  }
}