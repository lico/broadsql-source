package com.upandcoding.broadsql.dao.model.metadata;

import java.util.Objects;

/**
ResultSet getColumns(String catalog,
                   String schemaPattern,
                   String tableNamePattern,
                   String columnNamePattern)
                     throws SQLException

Retrieves a description of table columns available in the specified catalog.

Only column descriptions matching the catalog, schema, table and column name criteria are returned. They are ordered by TABLE_CAT,TABLE_SCHEM, TABLE_NAME, and ORDINAL_POSITION.

Each column description has the following columns:

    TABLE_CAT String => table catalog (may be null)
    TABLE_SCHEM String => table schema (may be null)
    TABLE_NAME String => table name
    COLUMN_NAME String => column name
    DATA_TYPE int => SQL type from java.sql.Types
    TYPE_NAME String => Data source dependent type name, for a UDT the type name is fully qualified
    COLUMN_SIZE int => column size.
    BUFFER_LENGTH is not used.
    DECIMAL_DIGITS int => the number of fractional digits. Null is returned for data types where DECIMAL_DIGITS is not applicable.
    NUM_PREC_RADIX int => Radix (typically either 10 or 2)
    NULLABLE int => is NULL allowed.
        columnNoNulls - might not allow NULL values
        columnNullable - definitely allows NULL values
        columnNullableUnknown - nullability unknown 
    REMARKS String => comment describing column (may be null)
    COLUMN_DEF String => default value for the column, which should be interpreted as a string when the value is enclosed in single quotes (may be null)
    SQL_DATA_TYPE int => unused
    SQL_DATETIME_SUB int => unused
    CHAR_OCTET_LENGTH int => for char types the maximum number of bytes in the column
    ORDINAL_POSITION int => index of column in table (starting at 1)
    IS_NULLABLE String => ISO rules are used to determine the nullability for a column.
        YES --- if the column can include NULLs
        NO --- if the column cannot include NULLs
        empty string --- if the nullability for the column is unknown 
    SCOPE_CATALOG String => catalog of table that is the scope of a reference attribute (null if DATA_TYPE isn't REF)
    SCOPE_SCHEMA String => schema of table that is the scope of a reference attribute (null if the DATA_TYPE isn't REF)
    SCOPE_TABLE String => table name that this the scope of a reference attribute (null if the DATA_TYPE isn't REF)
    SOURCE_DATA_TYPE short => source type of a distinct type or user-generated Ref type, SQL type from java.sql.Types (null if DATA_TYPE isn't DISTINCT or user-generated REF)
    IS_AUTOINCREMENT String => Indicates whether this column is auto incremented
        YES --- if the column is auto incremented
        NO --- if the column is not auto incremented
        empty string --- if it cannot be determined whether the column is auto incremented 
    IS_GENERATEDCOLUMN String => Indicates whether this is a generated column
        YES --- if this a generated column
        NO --- if this not a generated column
        empty string --- if it cannot be determined whether this is a generated column 

The COLUMN_SIZE column specifies the column size for the given column. For numeric data, this is the maximum precision. For character data, this is the length in characters. For datetime datatypes, this is the length in characters of the String representation (assuming the maximum allowed precision of the fractional seconds component). For binary data, this is the length in bytes. For the ROWID datatype, this is the length in bytes. Null is returned for data types where the column size is not applicable.

Parameters:
    catalog - a catalog name; must match the catalog name as it is stored in the database; "" retrieves those without a catalog; null means that the catalog name should not be used to narrow the search
    schemaPattern - a schema name pattern; must match the schema name as it is stored in the database; "" retrieves those without a schema; null means that the schema name should not be used to narrow the search
    tableNamePattern - a table name pattern; must match the table name as it is stored in the database
    columnNamePattern - a column name pattern; must match the column name as it is stored in the database
Returns:
    ResultSet - each row is a column description
Throws:
    SQLException - if a database access error occurs
See Also:
    getSearchStringEscape()
 */
public class ColumnMetadata implements Comparable {

	private String name; // COLUMN_NAME
	private String table; // TABLE_NAME
	private String catalog; // TABLE_CAT
	private String schema; // TABLE_SCHEM
	private int dataType; // DATA_TYPE
	private String typeName; // TYPE_NAME;
	private int columnSize; // COLUMN_SIZE
	private int decimalDigit; // DECIMAL_DIGITS
	private int numPrecRadix; // NUM_PREC_RADIX
	private int nullable; // NULLABLE
	private String remarks; // REMARKS
	private String defaultValue; // COLUMN_DEF
	private int indexInTable; // ORDINAL_POSITION
	private String isoNullable; // IS_NULLABLE
	private String isAutoIncrement; // IS_AUTOINCREMENT
	private String isGenerated; // IS_GENERATEDCOLUMN

	public ColumnMetadata() {

	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getTable() {
		return table;
	}

	public void setTable(String table) {
		this.table = table;
	}

	public String getCatalog() {
		return catalog;
	}

	public void setCatalog(String catalog) {
		this.catalog = catalog;
	}

	public String getSchema() {
		return schema;
	}

	public void setSchema(String schema) {
		this.schema = schema;
	}

	public int getDataType() {
		return dataType;
	}

	public void setDataType(int dataType) {
		this.dataType = dataType;
	}

	public String getTypeName() {
		return typeName;
	}

	public void setTypeName(String typeName) {
		this.typeName = typeName;
	}

	public int getColumnSize() {
		return columnSize;
	}

	public void setColumnSize(int columnSize) {
		this.columnSize = columnSize;
	}

	public int getDecimalDigit() {
		return decimalDigit;
	}

	public void setDecimalDigit(int decimalDigit) {
		this.decimalDigit = decimalDigit;
	}

	public int getNumPrecRadix() {
		return numPrecRadix;
	}

	public void setNumPrecRadix(int numPrecRadix) {
		this.numPrecRadix = numPrecRadix;
	}

	public int getNullable() {
		return nullable;
	}

	public void setNullable(int nullable) {
		this.nullable = nullable;
	}

	public String getRemarks() {
		return remarks;
	}

	public void setRemarks(String remarks) {
		this.remarks = remarks;
	}

	public String getDefaultValue() {
		return defaultValue;
	}

	public void setDefaultValue(String defaultValue) {
		this.defaultValue = defaultValue;
	}

	public int getIndexInTable() {
		return indexInTable;
	}

	public void setIndexInTable(int indexInTable) {
		this.indexInTable = indexInTable;
	}

	public String getIsoNullable() {
		return isoNullable;
	}

	public void setIsoNullable(String isoNullable) {
		this.isoNullable = isoNullable;
	}

	public String getIsAutoIncrement() {
		return isAutoIncrement;
	}

	public void setIsAutoIncrement(String isAutoIncrement) {
		this.isAutoIncrement = isAutoIncrement;
	}

	public String getIsGenerated() {
		return isGenerated;
	}

	public void setIsGenerated(String isGenerated) {
		this.isGenerated = isGenerated;
	}

	@Override
	public int hashCode() {
		int hash = 7;
		hash = 41 * hash + Objects.hashCode(this.name);
		hash = 41 * hash + Objects.hashCode(this.table);
		hash = 41 * hash + Objects.hashCode(this.catalog);
		hash = 41 * hash + Objects.hashCode(this.schema);
		return hash;
	}

	@Override
	public boolean equals(Object obj) {
		if (obj == null) {
			return false;
		}
		if (getClass() != obj.getClass()) {
			return false;
		}
		final ColumnMetadata other = (ColumnMetadata) obj;
		if (!Objects.equals(this.name, other.name)) {
			return false;
		}
		if (!Objects.equals(this.table, other.table)) {
			return false;
		}
		if (!Objects.equals(this.catalog, other.catalog)) {
			return false;
		}
		if (!Objects.equals(this.schema, other.schema)) {
			return false;
		}
		return true;
	}

	@Override
	public int compareTo(Object o) {
		int result = 0;
		if (o instanceof ColumnMetadata) {
			ColumnMetadata compared = (ColumnMetadata) o;
			result = this.name.compareToIgnoreCase(compared.getName());
		}
		return (result);
	}

}
