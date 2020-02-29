package com.columbia.adapter.soap.kafka;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.columbia.adapter.soap.dto.MktDataDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
@RefreshScope
public class KafkaProducer {

	private static final Logger LOGGER = LogManager.getLogger(KafkaProducer.class);

	@Value(value = "${spring.kafka.topicName}")
	private String topicName;

	@Autowired
	private KafkaTemplate<String, String> kTemplate;

	public void produceJsonData(MktDataDto dataObject) {
		ObjectMapper obj = new ObjectMapper();
		String message = null;
		try {
			message = obj.writeValueAsString(dataObject);
			LOGGER.info(">>Pushing Message {} to kafka topic", message);
			kTemplate.send(topicName, message);
			LOGGER.info("<<Message sent to topic {}", topicName);
		} catch (JsonProcessingException e) {
			LOGGER.info("Error occurred while sending data to kafka for ID: " + dataObject.getMktData().getSymbol());
		}
	}

}