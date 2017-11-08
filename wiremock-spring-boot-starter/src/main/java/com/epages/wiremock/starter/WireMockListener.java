package com.epages.wiremock.starter;

import static com.github.tomakehurst.wiremock.recording.RecordingStatus.Recording;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.StringUtils;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.common.ClasspathFileSource;
import com.github.tomakehurst.wiremock.extension.StubMappingTransformer;
import com.github.tomakehurst.wiremock.recording.RecordSpecBuilder;
import com.github.tomakehurst.wiremock.standalone.JsonFileMappingsSource;

class WireMockListener extends AbstractTestExecutionListener implements Ordered {

	private WireMockTest wireMockAnnotation;

	@Override
	public void prepareTestInstance(TestContext testContext) throws Exception {
		if(wireMockAnnotation == null) {
			return;
		}

		ConfigurableApplicationContext applicationContext = (ConfigurableApplicationContext) testContext
				.getApplicationContext();

		final WireMockServer wireMockServer = applicationContext.getBean(WireMockServer.class);
		wireMockServer.start();

		if (wireMockAnnotation.record() && !StringUtils.isEmpty(wireMockAnnotation.targetBaseUrl())) {

			wireMockServer.startRecording(new RecordSpecBuilder()
					.forTarget(wireMockAnnotation.targetBaseUrl())
					.transformers(new ArrayList<>(wireMockServer.getOptions().extensionsOfType(StubMappingTransformer.class).keySet()))
			);
		}
	}

	@Override
	public void beforeTestClass(TestContext testContext) throws Exception {
		wireMockAnnotation = testContext.getTestClass().getAnnotation(WireMockTest.class);
		if(wireMockAnnotation == null) {
			return;
		}

		int port = wireMockAnnotation.port() > 0 ? wireMockAnnotation.port() : InetUtils.getFreeServerPort();
		ArrayList<String> properties = new ArrayList<>();
		properties.add("wiremock.port=" + port);
		properties.add("wiremock.enabled=true");
		properties.add("wiremock.stubPath=" + wireMockAnnotation.stubPath());
		properties.add("ribbon.eureka.enabled=false");
		for (String service : (String[]) wireMockAnnotation.ribbonServices()) {
			properties.add(service + ".ribbon.listOfServers=localhost:" + port);
		}

		addPropertySourceProperties(testContext, properties.toArray(new String[0]));
	}

	@Override
	public void beforeTestMethod(TestContext testContext) throws Exception {
		if (wireMockAnnotation == null) {
			return;
		}
		WireMockTest methodAnnotation = testContext.getTestMethod().getAnnotation(WireMockTest.class);
		
		String stubPath = this.wireMockAnnotation.stubPath();

		if (methodAnnotation != null) {
			stubPath += "/" + methodAnnotation.stubPath();
		}

		ConfigurableApplicationContext applicationContext = (ConfigurableApplicationContext) testContext
				.getApplicationContext();

		WireMockServer server = applicationContext.getBean(WireMockServer.class);
		if (!isWireMockRecording(server)) {
			server.resetMappings();
			if (!stubPath.isEmpty()) {
				server.loadMappingsUsing(new JsonFileMappingsSource(new ClasspathFileSource(stubPath)));
			}
		}
	}

	private boolean isWireMockRecording(WireMockServer server) {
		return server.getRecordingStatus().getStatus().equals(Recording);
	}

	@Override
	public void afterTestMethod(TestContext testContext) throws Exception {
		if(wireMockAnnotation == null) {
			return;
		}
		ConfigurableApplicationContext applicationContext = (ConfigurableApplicationContext) testContext
				.getApplicationContext();

		WireMockServer wireMockServer = applicationContext.getBean(WireMockServer.class);
		if (!isWireMockRecording(wireMockServer)) {
			wireMockServer.resetToDefaultMappings();
		}
	}

	@Override
	public void afterTestClass(TestContext testContext) throws Exception {
		if(wireMockAnnotation == null) {
			return;
		}
		ConfigurableApplicationContext applicationContext = (ConfigurableApplicationContext) testContext
				.getApplicationContext();

		if (applicationContext.getBeanNamesForType(WireMockServer.class).length > 0) {
			WireMockServer wireMockServer = applicationContext.getBean(WireMockServer.class);
			if (isWireMockRecording(wireMockServer)) {
				wireMockServer.stopRecording();
			}
			wireMockServer.stop();
		}
	}

	private void addPropertySourceProperties(TestContext testContext, String[] properties) {
		try {
			MergedContextConfiguration configuration = (MergedContextConfiguration) ReflectionTestUtils
					.getField(testContext, "mergedContextConfiguration");
			new MergedContextConfigurationProperties(configuration).add(properties);
		} catch (RuntimeException ex) {
			throw ex;
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	static class MergedContextConfigurationProperties {

		private final MergedContextConfiguration configuration;

		MergedContextConfigurationProperties(MergedContextConfiguration configuration) {
			this.configuration = configuration;
		}

		void add(String[] properties, String... additional) {
			Set<String> merged = new LinkedHashSet<>(
					Arrays.asList(this.configuration.getPropertySourceProperties()));
			merged.addAll(Arrays.asList(properties));
			merged.addAll(Arrays.asList(additional));
			ReflectionTestUtils.setField(this.configuration, "propertySourceProperties",
					merged.toArray(new String[merged.size()]));
		}

	}

}
