package integration.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.IntegrationFlows;
import org.springframework.integration.file.FileHeaders;
import org.springframework.integration.file.dsl.Files;
import org.springframework.integration.file.transformer.FileToStringTransformer;
import org.springframework.integration.transformer.Transformer;
import org.springframework.messaging.support.MessageBuilder;

import java.io.File;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Requests from Dropbox that need to be processed by the Python processor.
 */

@Log4j2
@Configuration
class FilesToRequestsFlowConfiguration {

	private final PodcastIntegrationProperties properties;

	private final ObjectMapper objectMapper;

	FilesToRequestsFlowConfiguration(RabbitHelper helper, ObjectMapper om,
			PodcastIntegrationProperties properties) {

		this.properties = properties;
		this.objectMapper = om;

		helper.defineDestination(properties.getRequestsExchange(),
				properties.getRequestsQueue(), properties.getRequestsRoutingKey());
	}

	@Bean
	IntegrationFlow filesToRequestsFlow(RabbitRequestsFlowConfiguration requestsFlow) {

		var file = this.properties.getInboundPodcastsDirectory();

		var fileInboundAdapter = Files.inboundAdapter(file).autoCreateDirectory(true) //
				.get();

		return IntegrationFlows //
				.from(fileInboundAdapter,
						pc -> pc.poller(pm -> pm.fixedRate(500, TimeUnit.MILLISECONDS))) //
				.filter(File.class, this::isValidPodcastFile) //
				.transform(new FileToStringTransformer()) //
				.transform((Transformer) message -> {
					var json = (String) message.getPayload();
					var headers = message.getHeaders();
					return MessageBuilder.withPayload(toProductionRequest(headers, json))
							.copyHeadersIfAbsent(message.getHeaders()).build();
				})//
				.handle(new LoggingHandler()) //
				.channel(requestsFlow.rabbitRequestsChannel()) //
				.get();
	}

	private ProductionRequest toProductionRequest(Map<String, Object> headers,
			String json) {
		var manifestFile = (File) headers.get(FileHeaders.ORIGINAL_FILE);
		var body = this.fromJson(json);
		var nameOfFile = manifestFile.getName();
		return new ProductionRequest(body.getInterviewFile(), body.getIntroductionFile(),
				nameOfFile, body.getTimestamp(), body.getDescription());
	}

	private boolean isValidPodcastFile(File file) {
		return file != null && file.getName().toLowerCase().endsWith(".podcast")
				&& file.isFile() && file.length() > 0;
	}

	@SneakyThrows
	private ProductionRequest fromJson(String json) {
		return this.objectMapper.readValue(json, ProductionRequest.class);
	}

}