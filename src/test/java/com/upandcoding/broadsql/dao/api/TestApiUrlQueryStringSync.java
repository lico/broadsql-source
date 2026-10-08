package com.upandcoding.broadsql.dao.api;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.dao.api.ApiUrlQueryStringSync.RawQueryParam;
import com.upandcoding.broadsql.dao.api.ApiUrlQueryStringSync.UrlParts;
import com.upandcoding.broadsql.dao.api.model.ApiAttribute;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;

class TestApiUrlQueryStringSync {

	@Test
	void splitsABasePathWithNoQueryAndNoFragment() {
		UrlParts parts = ApiUrlQueryStringSync.split("${baseUrl}/users");
		Assertions.assertEquals("${baseUrl}/users", parts.basePath);
		Assertions.assertTrue(parts.queryParams.isEmpty());
		Assertions.assertNull(parts.fragment);
	}

	@Test
	void splitsBasePathAndQueryParameters() {
		UrlParts parts = ApiUrlQueryStringSync.split("${baseUrl}/products?limit=${limit}&expand=${expand}");
		Assertions.assertEquals("${baseUrl}/products", parts.basePath);
		Assertions.assertEquals(2, parts.queryParams.size());
		Assertions.assertEquals("limit", parts.queryParams.get(0).name);
		Assertions.assertEquals("${limit}", parts.queryParams.get(0).value);
		Assertions.assertEquals("expand", parts.queryParams.get(1).name);
		Assertions.assertEquals("${expand}", parts.queryParams.get(1).value);
	}

	@Test
	void splitsAFragmentWithoutLettingItBecomePartOfTheLastQueryValue() {
		UrlParts parts = ApiUrlQueryStringSync.split("https://host/resource?expand=${expand}#section");
		Assertions.assertEquals("https://host/resource", parts.basePath);
		Assertions.assertEquals(1, parts.queryParams.size());
		Assertions.assertEquals("expand", parts.queryParams.get(0).name);
		Assertions.assertEquals("${expand}", parts.queryParams.get(0).value, "the fragment must not leak into the query value");
		Assertions.assertEquals("section", parts.fragment);
	}

	@Test
	void splitsAFragmentWithNoQueryStringAtAll() {
		UrlParts parts = ApiUrlQueryStringSync.split("https://host/resource#section");
		Assertions.assertEquals("https://host/resource", parts.basePath);
		Assertions.assertTrue(parts.queryParams.isEmpty());
		Assertions.assertEquals("section", parts.fragment);
	}

	@Test
	void splitsOnTheFirstEqualsOnlySoAValueContainingEqualsSurvivesIntact() {
		UrlParts parts = ApiUrlQueryStringSync.split("${baseUrl}/x?filter=a=b=c");
		Assertions.assertEquals(1, parts.queryParams.size());
		Assertions.assertEquals("filter", parts.queryParams.get(0).name);
		Assertions.assertEquals("a=b=c", parts.queryParams.get(0).value);
	}

	@Test
	void distinguishesAnEmptyValueFromNoEqualsAtAll() {
		UrlParts withEquals = ApiUrlQueryStringSync.split("${baseUrl}/x?foo=");
		Assertions.assertEquals("", withEquals.queryParams.get(0).value);

		UrlParts withoutEquals = ApiUrlQueryStringSync.split("${baseUrl}/x?foo");
		Assertions.assertNull(withoutEquals.queryParams.get(0).value);
	}

	@Test
	void preservesDuplicateNamesInOrder() {
		UrlParts parts = ApiUrlQueryStringSync.split("${baseUrl}/x?tag=a&tag=b");
		Assertions.assertEquals(2, parts.queryParams.size());
		Assertions.assertEquals("a", parts.queryParams.get(0).value);
		Assertions.assertEquals("b", parts.queryParams.get(1).value);
	}

	@Test
	void doesNotEncodeOrDecodePlaceholders() {
		UrlParts parts = ApiUrlQueryStringSync.split("${baseUrl}/x?a=${valueWithSpecials}&b={{other}}");
		Assertions.assertEquals("${valueWithSpecials}", parts.queryParams.get(0).value);
		Assertions.assertEquals("{{other}}", parts.queryParams.get(1).value);
	}

	@Test
	void composesBasePathQueryAndFragmentInTheCorrectOrder() {
		String composed = ApiUrlQueryStringSync.compose("${baseUrl}/products",
				List.of(attribute("limit", "${limit}"), attribute("expand", "${expand}")), "section");
		Assertions.assertEquals("${baseUrl}/products?limit=${limit}&expand=${expand}#section", composed);
	}

	@Test
	void composesWithNoQueryParametersAndNoFragment() {
		String composed = ApiUrlQueryStringSync.compose("${baseUrl}/products", List.of(), null);
		Assertions.assertEquals("${baseUrl}/products", composed);
	}

	@Test
	void splitThenComposeRoundTripsAFullUrlWithFragmentAndPlaceholders() {
		String original = "https://host/resource?expand=${expand}&limit=${limit}#section";
		UrlParts parts = ApiUrlQueryStringSync.split(original);
		List<ApiAttribute> enabled = parts.queryParams.stream()
				.map(p -> attribute(p.name, p.value))
				.toList();
		String composed = ApiUrlQueryStringSync.compose(parts.basePath, enabled, parts.fragment);
		Assertions.assertEquals(original, composed);
	}

	@Test
	void groupByNamePreservesOrderAndMultiplicityForDuplicateNames() {
		UrlParts parts = ApiUrlQueryStringSync.split("${baseUrl}/x?tag=a&other=1&tag=b");
		Map<String, List<RawQueryParam>> grouped = ApiUrlQueryStringSync.groupByName(parts.queryParams);
		Assertions.assertEquals(List.of("tag", "other"), List.copyOf(grouped.keySet()));
		Assertions.assertEquals(2, grouped.get("tag").size());
		Assertions.assertEquals("a", grouped.get("tag").get(0).value);
		Assertions.assertEquals("b", grouped.get("tag").get(1).value);
		Assertions.assertEquals(1, grouped.get("other").size());
	}

	private ApiAttribute attribute(String name, String value) {
		return new ApiAttribute(ApiOwnerType.ENDPOINT, "1", ApiAttributeKind.QUERY_PARAMETER, name, value, false);
	}
}
