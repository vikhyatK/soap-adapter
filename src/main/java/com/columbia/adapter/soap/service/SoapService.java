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

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.ws.BindingProvider;

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

import com.bvc.ws.service9999.Service9999;
import com.bvc.ws.service9999.UserRequestMessageT;
import com.bvc.ws.service9999.UserResponseMessageT;
import com.columbia.adapter.soap.dto.Header;
import com.columbia.adapter.soap.dto.Info;
import com.columbia.adapter.soap.dto.MDEntryPx;
import com.columbia.adapter.soap.dto.MktData;
import com.columbia.adapter.soap.dto.MktDataDto;
import com.columbia.adapter.soap.kafka.KafkaProducer;

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

	@Value(value = "${spring.service9999.url}")
	private String service9999EndpointURL;
	
	@Value(value = "${spring.service9999.wsdl}")
	private String service9999EndpointWSDL;

	@Value(value = "${spring.service1074.action}")
	private String service1074Action;

	@Value(value = "${spring.service1074.url}")
	private String service1074EndpointURL;
	
	@Value(value = "${spring.fixml.marketdata.xsd}")
	private String fixmlMarketdataXSD;

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
		String token = callService9999();
		if (Objects.isNull(token)) {
			return;
		}
		Document document = callService1074(token);
		if (Objects.nonNull(document)) {
			processResults(document);
		}
	}

	private String callService9999() {
		URL wsdlLoc;
		try {
			wsdlLoc = new URL("file:" + new File(service9999EndpointWSDL));
			LOGGER.info(wsdlLoc.toString());
		} catch (IOException e) {
			LOGGER.error("Could not find wsdl for service 9999", e);
			return null;
		}

		Service9999 svc = new Service9999(wsdlLoc);
		com.bvc.ws.service9999.PortType port = svc.getPortTypeEndpoint();
		BindingProvider bp = (BindingProvider) port;
		bp.getRequestContext().put(BindingProvider.ENDPOINT_ADDRESS_PROPERTY, service9999EndpointURL);

		UserRequestMessageT request = createUserRequest();
		UserResponseMessageT loginResponse = port.logInOp(request);
		return loginResponse.getUserStatText();
	}

	private UserRequestMessageT createUserRequest() {
		UserRequestMessageT userRequest = new UserRequestMessageT();
		userRequest.setUsername(username);
		userRequest.setPassword(password);
		userRequest.setRawData(rawData);
		userRequest.setUserReqID(reqId);
		userRequest.setUserReqTyp(requestType);
		return userRequest;
	}

	private Document callService1074(String token) throws Exception {
		// Code to make a webservice HTTP request
		String responseString = "";
		String outputString = "";
		URL url = new URL(service1074EndpointURL.replace("token", token));
		setSSLContextToHttpsConnection();
		HttpsURLConnection httpsConnection = (HttpsURLConnection) url.openConnection();
		httpsConnection.setHostnameVerifier((arg0, arg1) -> false);
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		String xmlInput = "<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:SOAP-ENC=\"http://schemas.xmlsoap.org/soap/encoding/\" xmlns:ns1=\"http://www.bvc.com.co/Services/Service1074\" xmlns:ns2=\"http://www.bvc.com.co/BUS\" xmlns:ns3=\"http://www.fixprotocol.org/FIXML-5-0\" xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\r\n"
				+ "   <SOAP-ENV:Body>\r\n" + "      <ns2:firm reTrx=\"false\" orderId=\"" + getOrderIdStringFromFile()
				+ "\" id=\"" + firmId + "\" />\r\n" + "   </SOAP-ENV:Body>\r\n" + "</SOAP-ENV:Envelope>";

		byte[] buffer = new byte[xmlInput.length()];
		buffer = xmlInput.getBytes();
		bout.write(buffer);
		byte[] b = bout.toByteArray();
		// Set the appropriate HTTP parameters.
		httpsConnection.setRequestProperty("Content-Length", String.valueOf(b.length));
		httpsConnection.setRequestProperty("Content-Type", "text/xml; charset=utf-8");
		httpsConnection.setRequestProperty("SOAPAction", service1074Action);
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
		String formattedSOAPResponse = formatXML(outputString);
		System.out.println(formattedSOAPResponse);

		// Parse the String output to a org.w3c.dom.Document and be able to reach every
		// node with the org.w3c.dom API.
		return parseXmlFile(outputString);
	}

	private void setSSLContextToHttpsConnection() throws NoSuchAlgorithmException, KeyManagementException {
		SSLContext sslContext = SSLContext.getInstance("SSL");
        TrustManager[] trustAll
                = new TrustManager[] {new TrustAllCertificates()};
        sslContext.init(null, trustAll, new java.security.SecureRandom());
        HttpsURLConnection
        .setDefaultSSLSocketFactory(sslContext.getSocketFactory());
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

	private void processResults(Document document) {
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

	public String formatXML(String unformattedXml) throws Exception {
		try {
			Document document = parseXmlFile(unformattedXml);
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

	private Document parseXmlFile(String in) throws Exception {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setValidating(false);
			factory.setNamespaceAware(true);

			SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
			URL schemaUrl = new URL("file:" + new File(fixmlMarketdataXSD));
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
