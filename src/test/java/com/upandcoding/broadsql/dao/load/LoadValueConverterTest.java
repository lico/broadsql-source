package com.upandcoding.broadsql.dao.load;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Types;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class LoadValueConverterTest {

	private static LoadTargetColumn column(int jdbcType) {
		return new LoadTargetColumn("COL", jdbcType, true, false, false);
	}

	@Test
	void injectionLookingStringsAreStoredAsPlainStringData() {
		String hostile = "Robert'); DROP TABLE CUSTOMER; --";
		Object value = LoadValueConverter.convert(hostile, column(Types.VARCHAR));

		Assertions.assertEquals(hostile, value);
		Assertions.assertInstanceOf(String.class, value);
	}

	@Test
	void multilineAndUnicodeValuesAreStoredVerbatim() {
		String value = "line one\nline two éèà 中文";
		Assertions.assertEquals(value, LoadValueConverter.convert(value, column(Types.VARCHAR)));
	}

	@Test
	void literalNullKeywordBindsSqlNullForAnyType() {
		Assertions.assertNull(LoadValueConverter.convert("NULL", column(Types.VARCHAR)));
		Assertions.assertNull(LoadValueConverter.convert("null", column(Types.INTEGER)));
		Assertions.assertNull(LoadValueConverter.convert("Null", column(Types.DATE)));
	}

	@Test
	void emptyFieldBindsEmptyStringForCharacterColumnsAndNullOtherwise() {
		Assertions.assertEquals("", LoadValueConverter.convert("", column(Types.VARCHAR)));
		Assertions.assertNull(LoadValueConverter.convert("", column(Types.INTEGER)));
		Assertions.assertNull(LoadValueConverter.convert("", column(Types.DATE)));
	}

	@Test
	void convertsCommonNumericTypes() {
		Assertions.assertEquals(42, LoadValueConverter.convert("42", column(Types.INTEGER)));
		Assertions.assertEquals(42L, LoadValueConverter.convert("42", column(Types.BIGINT)));
		Assertions.assertEquals(new BigDecimal("19.99"), LoadValueConverter.convert("19.99", column(Types.NUMERIC)));
		Assertions.assertEquals(3.14, LoadValueConverter.convert("3.14", column(Types.DOUBLE)));
	}

	@Test
	void rejectsAnUnconvertibleNumericValue() {
		LoadValueConversionException ex = Assertions.assertThrows(LoadValueConversionException.class,
				() -> LoadValueConverter.convert("XYZ", column(Types.INTEGER)));
		Assertions.assertTrue(ex.getMessage().contains("cannot be converted"));
	}

	@Test
	void rejectsAnUnconvertibleDateValue() {
		Assertions.assertThrows(LoadValueConversionException.class, () -> LoadValueConverter.convert("ABC", column(Types.DATE)));
	}

	@Test
	void convertsAValidDate() {
		Object value = LoadValueConverter.convert("2026-09-12", column(Types.DATE));
		Assertions.assertInstanceOf(Date.class, value);
	}

	@Test
	void sysdateKeywordResolvesToTheCurrentDate() {
		Object value = LoadValueConverter.convert("sysdate", column(Types.DATE));
		Assertions.assertInstanceOf(Date.class, value);
	}

	@Test
	void convertsBooleanVariants() {
		Assertions.assertEquals(Boolean.TRUE, LoadValueConverter.convert("true", column(Types.BOOLEAN)));
		Assertions.assertEquals(Boolean.TRUE, LoadValueConverter.convert("Y", column(Types.BOOLEAN)));
		Assertions.assertEquals(Boolean.TRUE, LoadValueConverter.convert("1", column(Types.BOOLEAN)));
		Assertions.assertEquals(Boolean.FALSE, LoadValueConverter.convert("false", column(Types.BOOLEAN)));
		Assertions.assertEquals(Boolean.FALSE, LoadValueConverter.convert("N", column(Types.BOOLEAN)));
		Assertions.assertThrows(LoadValueConversionException.class, () -> LoadValueConverter.convert("maybe", column(Types.BOOLEAN)));
	}
}
