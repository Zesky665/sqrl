package org.apache.calcite.rel.type;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.calcite.sql.type.SqlTypeExplicitPrecedenceList;
import org.apache.calcite.sql.type.SqlTypeName;

public class DynamicRecordTypeImpl2 extends DynamicRecordType {
  private final RelDataTypeFactory typeFactory;
  private final DynamicRecordTypeImpl2 parent;
  private Map<String, RelDataTypeField> fieldMap = new HashMap<>();
  private List<RelDataTypeField> fields = new ArrayList<>();
  private boolean struct;


  /** Creates a DynamicRecordTypeImpl. */
  public DynamicRecordTypeImpl2(RelDataTypeFactory typeFactory,
      DynamicRecordTypeImpl2 parent) {
    this.typeFactory = typeFactory;
    this.parent = parent;
    computeDigest();
  }

  @Override public List<RelDataTypeField> getFieldList() {
    return fields;
  }

  @Override public int getFieldCount() {
    return fields.size();
  }

  @Override public RelDataTypeField getField(String fieldName,
      boolean caseSensitive, boolean elideRecord) {
    if (fieldMap.containsKey(fieldName)) {
      return fieldMap.get(fieldName);
    }

    RelDataTypeField newField;
    if (fieldName.equalsIgnoreCase("b")) {
      newField = new RelDataTypeFieldImpl("b", 0, this.typeFactory.createSqlType(SqlTypeName.BOOLEAN));
//      setStruct(false);
    } else {
      //hierarchical
      newField = new RelDataTypeFieldImpl(fieldName, this.fields.size(), new DynamicRecordTypeImpl2(typeFactory, this));

    }
    this.fields.add(newField);
    this.fieldMap.put(fieldName, newField);

    // If a new field is added, we should re-compute the digest.
    if (parent != null) {
      parent.computeDigest();
    }
    computeDigest();
    return newField;
  }

  private void setStruct(boolean b) {
    if (parent != null) {
      parent.setStruct(b);
    }
    this.struct = b;
  }

  @Override public List<String> getFieldNames() {
    return fields.stream().map(e->e.getName()).collect(Collectors.toList());
  }

  @Override public SqlTypeName getSqlTypeName() {
    return SqlTypeName.ROW;
  }

  @Override public RelDataTypePrecedenceList getPrecedenceList() {
    return new SqlTypeExplicitPrecedenceList(Collections.<SqlTypeName>emptyList());
  }

  protected void generateTypeString(StringBuilder sb, boolean withDetail) {
    sb.append("(DynamicRecordRow").append(")");
  }

  public List<String> getFieldNamesRecursive() {
//    List<String> str = new ArrayList<>(fields.stream().map(e->e.getName()).collect(Collectors.toList()));
    return null;
  }

  @Override public boolean isStruct() {
    return true;
  }

  @Override public RelDataTypeFamily getFamily() {
    return getSqlTypeName().getFamily();
  }


  public StructKind getStructKind() {
    return StructKind.PEEK_FIELDS;
  }

}