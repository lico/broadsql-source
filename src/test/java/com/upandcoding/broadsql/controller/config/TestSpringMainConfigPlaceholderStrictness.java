package com.upandcoding.broadsql.controller.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

/**
 * SPRINT XT02A corrective pass (16/09/2026, see docs/TECHNICAL_CHANGE.md): proves
 * {@link SpringMainConfig#placeHolderConfigurer()} was correctly reverted to Spring's default, strict
 * placeholder-resolution behavior - a genuinely unresolvable {@code ${...}} placeholder on an
 * <i>unrelated</i> property must still fail application startup loudly, exactly as it always did
 * before {@code apiproxyusername}/{@code apiproxypassword} needed their own, narrower fix (reading
 * directly from the raw INI file in {@code ConsoleSettings}, never through this configurer at all -
 * see {@code TestConsoleSettingsXt02a}). An earlier version of this method called
 * {@code setIgnoreUnresolvablePlaceholders(true)} globally, which would have made this exact scenario
 * silently succeed instead - this test is the regression guard against that ever coming back.
 *
 * <p>Deliberately a minimal, standalone Spring context (not the full {@code SpringMainConfig}, which
 * would require the real INI file/CDF vaults) - only {@link SpringMainConfig#placeHolderConfigurer()}
 * itself and a throwaway bean are under test.
 */
class TestSpringMainConfigPlaceholderStrictness {

	@Configuration
	static class MinimalConfigUnderTest {
		@Bean
		static org.springframework.context.support.PropertySourcesPlaceholderConfigurer placeHolderConfigurer() {
			return SpringMainConfig.placeHolderConfigurer();
		}

		@Bean
		BeanWithAnUnresolvablePlaceholder beanWithAnUnresolvablePlaceholder() {
			return new BeanWithAnUnresolvablePlaceholder();
		}
	}

	static class BeanWithAnUnresolvablePlaceholder {
		@Value("${totally.unrelated.undefined.property}")
		String value;
	}

	@Test
	void anUnrelatedGenuinelyUndefinedPlaceholderStillFailsApplicationStartup() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", java.util.Map.of()));
			context.register(MinimalConfigUnderTest.class);
			Assertions.assertThrows(BeanCreationException.class, context::refresh,
					"an unrelated, genuinely undefined ${...} placeholder must still fail startup loudly - "
							+ "the apiproxyusername/apiproxypassword fix must not have weakened this application-wide");
		}
	}

	@Test
	void aDefinedPlaceholderStillResolvesNormally() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.getEnvironment().getPropertySources()
					.addFirst(new MapPropertySource("test", java.util.Map.of("totally.unrelated.undefined.property", "resolved-value")));
			context.register(MinimalConfigUnderTest.class);
			Assertions.assertDoesNotThrow(context::refresh);
			BeanWithAnUnresolvablePlaceholder bean = context.getBean(BeanWithAnUnresolvablePlaceholder.class);
			Assertions.assertEquals("resolved-value", bean.value);
		}
	}
}
