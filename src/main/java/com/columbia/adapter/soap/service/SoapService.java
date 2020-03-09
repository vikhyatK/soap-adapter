package com.columbia.adapter.soap.service;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Objects;
import java.util.Scanner;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import org.apache.xml.serialize.OutputFormat;
import org.apache.xml.serialize.XMLSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import com.columbia.adapter.soap.dto.Header;
import com.columbia.adapter.soap.dto.Info;
import com.columbia.adapter.soap.dto.MDEntryPx;
import com.columbia.adapter.soap.dto.MktData;
import com.columbia.adapter.soap.dto.MktDataDto;
import com.columbia.adapter.soap.kafka.KafkaProducer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
@RefreshScope
public class SoapService {

	private static final Logger LOGGER = LoggerFactory.getLogger(SoapService.class);

	private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("HH:mm:ss");

	private static final String NAMESPACE = "http://www.fixprotocol.org/FIXML-5-0";

	private static Integer orderId = 0;

	@Autowired
	private KafkaProducer kafkaProducer;

	@Value(value = "${spring.firm.id}")
	private String firmId;

	@Value(value = "${spring.service1074.action}")
	private String service1074Action;

	@Value(value = "${spring.service1074.url}")
	private String service1074EndpointURL;

	@Value(value = "${spring.fixml.marketdata.xsd}")
	private String fixmlMarketdataXSD;
	
	@Value(value = "${spring.service9999.action}")
	private String service9999Action;

	@Value(value = "${spring.service9999.url}")
	private String service9999EndpointURL;

	@Value(value = "${spring.fixml.components.xsd}")
	private String fixmlComponentsXSD;

	@Value(value = "${spring.user.name}")
	private String username;

	@Value(value = "${spring.user.password}")
	private String password;

	@Value(value = "${spring.user.raw_data}")
	private String rawData;

	@Value(value = "${spring.user.requesttype}")
	private int requestType;

	@Value(value = "${spring.user.reqid}")
	private String reqId;

	@Scheduled(cron = "${spring.cron.time}")
	public void callSoapService() throws Exception {
		LOGGER.info("Calling SOAP for orderId {} at time {}", orderId, DATE_FORMAT.format(new Date()));
		URL url9999 = new URL(service9999EndpointURL);
		String xmlInput9999 = "<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"http://schemas.xmlsoap.org/soap/envelope/\"> <SOAP-ENV:Body>\r\n"
				+ "<UserReq xmlns=\"http://www.fixprotocol.org/FIXML-5-0\" Password=\"" + password + "\"  RawData=\""
				+ rawData + "\" UserReqID=\"" + reqId + "\" UserReqTyp=\"" + requestType + "\" Username=\"" + username
				+ "\" />\r\n" + "</SOAP-ENV:Body></SOAP-ENV:Envelope>";
		Document document9999 = callService(url9999, fixmlComponentsXSD, xmlInput9999, service9999Action);
		
		if (Objects.isNull(document9999)) {
			LOGGER.error("Response of Service9999 is null");
			return;
		}
		String token = processResults9999(document9999);
		if(Objects.isNull(token)) {
			LOGGER.error("Token returned by Service9999 is null");
			return;
		}
		
		URL url1074 = new URL(service1074EndpointURL.replace("token", token));
		String xmlInput1074 = "<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:SOAP-ENC=\"http://schemas.xmlsoap.org/soap/encoding/\" xmlns:ns1=\"http://www.bvc.com.co/Services/Service1074\" xmlns:ns2=\"http://www.bvc.com.co/BUS\" xmlns:ns3=\"http://www.fixprotocol.org/FIXML-5-0\" xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\r\n"
				+ "   <SOAP-ENV:Body>\r\n" + "      <ns2:firm reTrx=\"false\" orderId=\"" + getOrderIdStringFromFile()
				+ "\" id=\"" + firmId + "\" />\r\n" + "   </SOAP-ENV:Body>\r\n" + "</SOAP-ENV:Envelope>";
		Document document1074 = callService(url1074, fixmlMarketdataXSD, xmlInput1074, service1074Action);
		if (Objects.nonNull(document1074)) {
			processResults1074(document1074);
		}
	}

	private String processResults9999(Document document) throws JsonProcessingException {
		NodeList userRsp = document.getElementsByTagNameNS(NAMESPACE, "UserRsp");
		Node fixmlNode = userRsp.item(0);
		NamedNodeMap attributes = fixmlNode.getAttributes();
		String userStatText = attributes.getNamedItem("UserStatText").getNodeValue();
		LOGGER.info("The token returned from Service9999 is : {}", userStatText);
		return userStatText;
	}

	private Document callService(URL url, String xsd, String xmlInput, String action) throws Exception {
		// Code to make a webservice HTTP request
		String responseString = "";
		String outputString = "";
		setSSLContextToHttpsConnection();
		HttpsURLConnection httpsConnection = (HttpsURLConnection) url.openConnection();
		httpsConnection.setHostnameVerifier((arg0, arg1) -> true);
		ByteArrayOutputStream bout = new ByteArrayOutputStream();

		byte[] buffer = new byte[xmlInput.length()];
		buffer = xmlInput.getBytes();
		bout.write(buffer);
		byte[] b = bout.toByteArray();
		// Set the appropriate HTTP parameters.
		httpsConnection.setRequestProperty("Content-Length", String.valueOf(b.length));
		httpsConnection.setRequestProperty("Content-Type", "text/xml; charset=utf-8");
		httpsConnection.setRequestProperty("SOAPAction", action);
		httpsConnection.setRequestMethod("POST");
		httpsConnection.setDoOutput(true);
		httpsConnection.setDoInput(true);
		OutputStream out = httpsConnection.getOutputStream();
		// Write the content of the request to the outputstream of the HTTP Connection.
		out.write(b);
		out.close();
		// Ready with sending the request.

		// Read the response.
		InputStreamReader isr = new InputStreamReader(httpsConnection.getInputStream());
		BufferedReader in = new BufferedReader(isr);

		// Write the SOAP message response to a String.
		while ((responseString = in.readLine()) != null) {
			outputString = outputString + responseString;
		}

		// Write the SOAP message formatted to the console.
		String formattedSOAPResponse = formatXML(outputString, xsd);
		System.out.println(formattedSOAPResponse);

		// Parse the String output to a org.w3c.dom.Document and be able to reach every
		// node with the org.w3c.dom API.
		return parseXmlFile(outputString, xsd);
	}

	private void setSSLContextToHttpsConnection() throws NoSuchAlgorithmException, KeyManagementException {
		SSLContext sslContext = SSLContext.getInstance("SSL");
		TrustManager[] trustAll = new TrustManager[] { new TrustAllCertificates() };
		sslContext.init(null, trustAll, new java.security.SecureRandom());
		HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
	}

	private String getOrderIdStringFromFile() {
		String orderIdString = "0";
		if (orderId == 0) {
			File file = new File(getClass().getResource("/orderid.txt").getFile());
			try (Scanner sc = new Scanner(file)) {
				if (sc.hasNext()) {
					orderIdString = sc.next();
				}
			} catch (FileNotFoundException e) {
				LOGGER.error("Error occurred file opening the orderid file.", e);
			}
		}
		return orderIdString;
	}

	private void processResults1074(Document document) {
		NodeList FIXML = document.getElementsByTagNameNS(NAMESPACE, "FIXML");
		Node fixmlNode = FIXML.item(0);
		NamedNodeMap attributes = fixmlNode.getAttributes();
		String xr = attributes.getNamedItem("xr").getNodeValue();
		orderId = Integer.parseInt(xr.split("|")[xr.split("|").length - 1].split("-")[1]) + 1;
		writeOrderIdToFile();

		NodeList mktDataFullList = document.getElementsByTagNameNS(NAMESPACE, "MktDataFull");
		if (mktDataFullList.getLength() == 1) {
			Element mktDataFullElement = (Element) mktDataFullList.item(0);
			NamedNodeMap hdrAttributes = mktDataFullElement.getElementsByTagNameNS(NAMESPACE, "Hdr").item(0)
					.getAttributes();
			if (hdrAttributes.getNamedItem("MdlMsg").getNodeValue()
					.equals("No existen respuestas aser entregadas al afiliado")) {
				LOGGER.info("Message recieved with for SID : {}", hdrAttributes.getNamedItem("SID").getNodeValue());
				return;
			}
			convertToJsonAndSendToKafka(mktDataFullElement);
		} else {
			for (int i = 0; i < mktDataFullList.getLength(); i++) {
				Element mktDataFullElement = (Element) mktDataFullList.item(i);
				convertToJsonAndSendToKafka(mktDataFullElement);
			}
			LOGGER.info("All the data pushed to Kafka successfully.");
		}

	}

	private void convertToJsonAndSendToKafka(Element mktDataFullElement) {
		NamedNodeMap hdrAttributes = mktDataFullElement.getElementsByTagNameNS(NAMESPACE, "Hdr").item(0)
				.getAttributes();
		NamedNodeMap instrmtAttributes = mktDataFullElement.getElementsByTagNameNS(NAMESPACE, "Instrmt").item(0)
				.getAttributes();
		NamedNodeMap fullAttributes = mktDataFullElement.getElementsByTagNameNS(NAMESPACE, "Full").item(0)
				.getAttributes();
		NodeList infoNodes = mktDataFullElement.getElementsByTagNameNS(NAMESPACE, "Info");

		Header header = new Header();
		header.setExecTime(hdrAttributes.getNamedItem("OrigSnt").getNodeValue());
		header.setMdlMsg(hdrAttributes.getNamedItem("MdlMsg").getNodeValue());

		MDEntryPx first = new MDEntryPx();
		first.setMDEntryType("8");
		first.setMDEntryPx(Double.parseDouble(fullAttributes.getNamedItem("LowPx").getNodeValue()));

		MDEntryPx second = new MDEntryPx();
		second.setMDEntryType("t");
		second.setMDEntryPx(Double.parseDouble(fullAttributes.getNamedItem("PxDelta").getNodeValue()));

		MktData mktData = new MktData();
		mktData.setSymbol(instrmtAttributes.getNamedItem("Sym").getNodeValue());
		mktData.setLastPx(Double.parseDouble(fullAttributes.getNamedItem("Px").getNodeValue()));
		mktData.setMDEntryPx(Arrays.asList(first, second));

		Info info = new Info();
		for (int i = 0; i < 2; i++) {
			Node infoNode = infoNodes.item(i);
			String infoType = infoNode.getAttributes().getNamedItem("InfoTyp").getNodeValue();
			String infoId = infoNode.getAttributes().getNamedItem("InfoID").getNodeValue();
			if (infoType.equals("70")) {
				info.setDuration(Double.parseDouble(infoId));
			} else {
				info.setTir(Double.parseDouble(infoId));
			}
		}

		MktDataDto dto = new MktDataDto();
		dto.setHeader(header);
		dto.setMktData(mktData);
		dto.setInfo(info);
		kafkaProducer.produceJsonData(dto);
	}

	private void writeOrderIdToFile() {
		File file = new File(getClass().getResource("/orderid.txt").getFile());
		try (FileWriter fw = new FileWriter(file)) {
			fw.write(orderId);
		} catch (IOException e) {
			LOGGER.error("Error occurred file writing the orderid to file.", e);
		}
	}

	public String formatXML(String unformattedXml, String xsd) throws Exception {
		try {
			Document document = parseXmlFile(unformattedXml, xsd);
			OutputFormat format = new OutputFormat(document);
			format.setIndenting(true);
			format.setIndent(3);
			format.setOmitXMLDeclaration(true);
			Writer out = new StringWriter();
			XMLSerializer serializer = new XMLSerializer(out, format);
			serializer.serialize(document);
			return out.toString();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private Document parseXmlFile(String in, String xsd) throws Exception {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setValidating(false);
			factory.setNamespaceAware(true);

			SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
			URL schemaUrl = new URL("file:" + new File(xsd));
			Schema schema = schemaFactory.newSchema(schemaUrl);
			factory.setSchema(schema);

			DocumentBuilder builder = factory.newDocumentBuilder();
			builder.setErrorHandler(new ErrorHandler() {
				public void warning(SAXParseException e) throws SAXException {
					System.out.println("WARNING: " + e.getMessage()); // do nothing
				}

				public void error(SAXParseException e) throws SAXException {
					System.out.println("ERROR: " + e.getMessage());
					throw e;
				}

				public void fatalError(SAXParseException e) throws SAXException {
					System.out.println("FATAL: " + e.getMessage());
					throw e;
				}
			}

			);
			InputSource inputSource = new InputSource(in);
			System.out.println(inputSource.toString());
			return builder.parse(inputSource);
		} catch (ParserConfigurationException | IOException | SAXException e) {
			throw e;
		}
	}

	private static class TrustAllCertificates implements X509TrustManager {
		public void checkClientTrusted(X509Certificate[] certs, String authType) {
		}

		public void checkServerTrusted(X509Certificate[] certs, String authType) {
		}

		public X509Certificate[] getAcceptedIssuers() {
			return null;
		}
	}
}
