package com.suez.keycloak.mapper;

import org.keycloak.dom.saml.v2.assertion.AttributeStatementType;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.saml.mappers.AbstractSAMLProtocolMapper;
import org.keycloak.protocol.saml.mappers.AttributeStatementHelper;
import org.keycloak.protocol.saml.mappers.SAMLAttributeStatementMapper;
import org.keycloak.dom.saml.v2.assertion.AttributeType;
import org.keycloak.provider.ProviderConfigProperty;
import org.jboss.logging.Logger;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

public class SamlRestAttributeMapper extends AbstractSAMLProtocolMapper implements SAMLAttributeStatementMapper {

    private static final Logger logger = Logger.getLogger(SamlRestAttributeMapper.class);
    private static final Gson gson = new Gson();

    private static final String PROVIDER_ID = "saml-rest-attribute-mapper";

    // Configuration properties
    private static final String REST_URL = "rest.url";
    private static final String AUTH_TOKEN = "auth.token";
    private static final String ATTRIBUTE_NAME = "attribute.name";
    private static final String ATTRIBUTE_NAMEFORMAT = "attribute.nameformat";
    private static final String JSON_PATH = "json.path";
    private static final String HTTP_METHOD = "http.method";
    private static final String TIMEOUT = "timeout";

    private static final List<ProviderConfigProperty> configProperties = new ArrayList<ProviderConfigProperty>();

    static {
        ProviderConfigProperty restUrlProperty = new ProviderConfigProperty();
        restUrlProperty.setName(REST_URL);
        restUrlProperty.setLabel("REST URL");
        restUrlProperty.setHelpText("URL of the REST service to call. Available variables: ${username}, ${email}, ${userId}");
        restUrlProperty.setType(ProviderConfigProperty.STRING_TYPE);
        restUrlProperty.setRequired(Boolean.TRUE);
        configProperties.add(restUrlProperty);

        ProviderConfigProperty tokenProperty = new ProviderConfigProperty();
        tokenProperty.setName(AUTH_TOKEN);
        tokenProperty.setLabel("Authentication Token");
        tokenProperty.setHelpText("Bearer token for authentication (optional)");
        tokenProperty.setType(ProviderConfigProperty.STRING_TYPE);
        tokenProperty.setSecret(true);
        configProperties.add(tokenProperty);

        ProviderConfigProperty methodProperty = new ProviderConfigProperty();
        methodProperty.setName(HTTP_METHOD);
        methodProperty.setLabel("HTTP Method");
        methodProperty.setHelpText("HTTP method (GET or POST)");
        methodProperty.setType(ProviderConfigProperty.LIST_TYPE);
        methodProperty.setDefaultValue("GET");
        methodProperty.setOptions(List.of("GET", "POST"));
        methodProperty.setRequired(Boolean.TRUE);
        configProperties.add(methodProperty);

        ProviderConfigProperty timeoutProperty = new ProviderConfigProperty();
        timeoutProperty.setName(TIMEOUT);
        timeoutProperty.setLabel("Timeout (ms)");
        timeoutProperty.setHelpText("Timeout for REST call in milliseconds");
        timeoutProperty.setType(ProviderConfigProperty.STRING_TYPE);
        timeoutProperty.setDefaultValue("5000");
        configProperties.add(timeoutProperty);

        ProviderConfigProperty attributeNameProperty = new ProviderConfigProperty();
        attributeNameProperty.setName(ATTRIBUTE_NAME);
        attributeNameProperty.setLabel("SAML Attribute Name");
        attributeNameProperty.setHelpText(
            "Used only if the response path returns a primitive or an array. " +
                "For JSON objects, attribute names are taken from the JSON keys."
        );
        attributeNameProperty.setType(ProviderConfigProperty.STRING_TYPE);
        configProperties.add(attributeNameProperty);

        ProviderConfigProperty nameFormatProperty = new ProviderConfigProperty();
        nameFormatProperty.setName(ATTRIBUTE_NAMEFORMAT);
        nameFormatProperty.setLabel("SAML Attribute Name Format");
        nameFormatProperty.setHelpText("URI format of the SAML attribute");
        nameFormatProperty.setType(ProviderConfigProperty.LIST_TYPE);
        nameFormatProperty.setDefaultValue(AttributeStatementHelper.BASIC);
        nameFormatProperty.setOptions(List.of(
            AttributeStatementHelper.BASIC,
            AttributeStatementHelper.URI_REFERENCE,
            AttributeStatementHelper.UNSPECIFIED
        ));
        configProperties.add(nameFormatProperty);

        ProviderConfigProperty jsonPathProperty = new ProviderConfigProperty();
        jsonPathProperty.setName(JSON_PATH);
        jsonPathProperty.setLabel("JSON Path");
        jsonPathProperty.setHelpText("Path to extract value from JSON response (e.g., data.userRole). Leave empty to use entire response");
        jsonPathProperty.setType(ProviderConfigProperty.STRING_TYPE);
        configProperties.add(jsonPathProperty);
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return configProperties;
    }

    public static List<ProviderConfigProperty> getConfigPropertiesStatic() {
        return configProperties;
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "SAML REST Attribute";
    }

    @Override
    public String getDisplayCategory() {
        return "Attribute Mapper";
    }

    @Override
    public String getHelpText() {
        return "Fetches a value via REST call and adds it as a SAML attribute";
    }

    @Override
    public void transformAttributeStatement(AttributeStatementType attributeStatement,
                                            ProtocolMapperModel mappingModel,
                                            KeycloakSession session,
                                            UserSessionModel userSession,
                                            AuthenticatedClientSessionModel clientSession) {

        logger.info("REST SAML Mapper - START");

        String restUrl = mappingModel.getConfig().get(REST_URL);
        String authToken = mappingModel.getConfig().get(AUTH_TOKEN);
        String attributeName = mappingModel.getConfig().get(ATTRIBUTE_NAME);
        String attributeNameFormat = mappingModel.getConfig().get(ATTRIBUTE_NAMEFORMAT);
        String jsonPath = mappingModel.getConfig().get(JSON_PATH);
        String httpMethod = mappingModel.getConfig().getOrDefault(HTTP_METHOD, "GET");
        String timeoutStr = mappingModel.getConfig().getOrDefault(TIMEOUT, "5000");

        if (restUrl == null || restUrl.isEmpty()) {
            logger.warn("REST URL not configured for mapper: " + mappingModel.getName());
            return;
        }

        try {
            int timeout = Integer.parseInt(timeoutStr);
            // Replace variables in URL
            String finalUrl = replaceVariables(restUrl, userSession);
            logger.infof("Final URL: %s", finalUrl);

            // Perform REST call
            String jsonResponse = performRestCall(finalUrl, authToken, httpMethod, timeout);
            logger.infof("REST Response: %s", jsonResponse);

            // Parse JSON
            JsonElement rootElement = JsonParser.parseString(jsonResponse);

            // Navigate to the specified path
            JsonElement targetElement = rootElement;
            if (jsonPath != null && !jsonPath.isEmpty()) {
                targetElement = navigateJsonPath(rootElement, jsonPath);
                logger.infof("Navigated to path, type: %s", targetElement.getClass().getSimpleName());
            }

            // Process the target element and add SAML attributes
            if (targetElement.isJsonObject()) {
                // Object: each property becomes a separate SAML attribute
                JsonObject obj = targetElement.getAsJsonObject();
                logger.infof("Processing JSON object with %d properties", obj.size());

                for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                    String attrName = entry.getKey();
                    List<String> values = extractValues(entry.getValue());

                    if (!values.isEmpty()) {
                        addSamlAttribute(attributeStatement, attrName, values, attributeNameFormat);
                        logger.infof("Added attribute '%s' with %d value(s)", attrName, values.size());
                    }
                }

            } else if (targetElement.isJsonArray()) {
                // Array: use attribute name from config
                if (attributeName == null || attributeName.isEmpty()) {
                    logger.error("Attribute name required for array values");
                    return;
                }

                List<String> values = extractValues(targetElement);
                addSamlAttribute(attributeStatement, attributeName, values, attributeNameFormat);
                logger.infof("Added attribute '%s' with %d value(s) from array", attributeName, values.size());

            } else {
                // Primitive: use attribute name from config
                if (attributeName == null || attributeName.isEmpty()) {
                    logger.error("Attribute name required for primitive values");
                    return;
                }

                List<String> values = extractValues(targetElement);
                addSamlAttribute(attributeStatement, attributeName, values, attributeNameFormat);
                logger.infof("Added attribute '%s' with value: %s", attributeName, values.get(0));
            }

            logger.info("SUCCESS - All attributes added to SAML response");
        } catch (Exception e) {
            logger.errorf(e, "Error during REST call for mapper %s", mappingModel.getName());
        }
        logger.info("REST SAML Mapper - END");
    }

    /**
     * Navigate through JSON using a path expression
     * Supports: property access (user.name), array indexing (items[0]), and combinations
     */
    private JsonElement navigateJsonPath(JsonElement element, String path) {
        String[] parts = splitPath(path);
        JsonElement current = element;

        for (String part : parts) {
            if (part.contains("[")) {
                // Array access: key[index]
                int bracketPos = part.indexOf('[');
                String key = part.substring(0, bracketPos);
                int index = Integer.parseInt(part.substring(bracketPos + 1, part.indexOf(']')));

                if (!key.isEmpty() && current.isJsonObject()) {
                    current = current.getAsJsonObject().get(key);
                }

                if (current != null && current.isJsonArray()) {
                    current = current.getAsJsonArray().get(index);
                }

            } else {
                // Property access
                if (current != null && current.isJsonObject()) {
                    current = current.getAsJsonObject().get(part);
                }
            }

            if (current == null) {
                throw new RuntimeException("Path not found: " + path + " (stopped at: " + part + ")");
            }
        }

        return current;
    }

    /**
     * Split path by dots, preserving array indices
     * Example: "hydra:member[0].accounts" -> ["hydra:member[0]", "accounts"]
     */
    private String[] splitPath(String path) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inBracket = false;

        for (char c : path.toCharArray()) {
            if (c == '[') {
                inBracket = true;
                current.append(c);
            } else if (c == ']') {
                inBracket = false;
                current.append(c);
            } else if (c == '.' && !inBracket) {
                if (current.length() > 0) {
                    parts.add(current.toString());
                    current = new StringBuilder();
                }
            } else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            parts.add(current.toString());
        }

        return parts.toArray(new String[0]);
    }

    /**
     * Extract string values from a JsonElement
     * - Primitive: returns single value
     * - Array: returns all elements as strings
     * - Object: returns JSON string representation
     */
    private List<String> extractValues(JsonElement element) {
        List<String> values = new ArrayList<>();

        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isString()) {
                values.add(primitive.getAsString());
            } else if (primitive.isNumber()) {
                values.add(String.valueOf(primitive.getAsNumber()));
            } else if (primitive.isBoolean()) {
                values.add(String.valueOf(primitive.getAsBoolean()));
            }

        } else if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement item : array) {
                if (item.isJsonPrimitive()) {
                    JsonPrimitive primitive = item.getAsJsonPrimitive();
                    if (primitive.isString()) {
                        values.add(primitive.getAsString());
                    } else if (primitive.isNumber()) {
                        values.add(String.valueOf(primitive.getAsNumber()));
                    } else if (primitive.isBoolean()) {
                        values.add(String.valueOf(primitive.getAsBoolean()));
                    }
                } else {
                    // Complex object/array in array - convert to JSON string
                    values.add(gson.toJson(item));
                }
            }

        } else if (element.isJsonObject()) {
            // Object - convert to JSON string
            values.add(gson.toJson(element));

        } else if (element.isJsonNull()) {
            // Skip null values
        }

        return values;
    }

    /**
     * Add a SAML attribute with one or more values
     */
    private void addSamlAttribute(AttributeStatementType attributeStatement,
                                  String attributeName,
                                  List<String> values,
                                  String nameFormat) {

        if (values.isEmpty()) {
            return;
        }

        AttributeType attribute = new AttributeType(attributeName);

        if (nameFormat != null && !nameFormat.isEmpty()) {
            attribute.setNameFormat(nameFormat);
        } else {
            attribute.setNameFormat("urn:oasis:names:tc:SAML:2.0:attrname-format:basic");
        }

        // Add all values
        for (String value : values) {
            attribute.addAttributeValue(value);
        }

        attributeStatement.addAttribute(new AttributeStatementType.ASTChoiceType(attribute));
    }

    private String performRestCall(String urlString, String authToken, String method, int timeout) throws Exception {
        logger.infof("HTTP %s request to: %s", method, urlString);

        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(timeout);
        conn.setReadTimeout(timeout);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Content-Type", "application/json");

        if (authToken != null && !authToken.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + authToken);
        }

        int responseCode = conn.getResponseCode();
        logger.infof("HTTP Response Code: %d", responseCode);

        if (responseCode == 200) {
            try (BufferedReader in = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder response = new StringBuilder();
                String inputLine;

                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }

                return response.toString();
            }
        } else {
            String errorMsg = "REST call failed with code: " + responseCode;
            try (BufferedReader in = new BufferedReader(
                new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                StringBuilder errorResponse = new StringBuilder();
                String inputLine;

                while ((inputLine = in.readLine()) != null) {
                    errorResponse.append(inputLine);
                }
                errorMsg += ", Error: " + errorResponse.toString();
            } catch (Exception e) {
                // Ignore
            }
            throw new Exception(errorMsg);
        }
    }

    private String replaceVariables(String url, UserSessionModel userSession) {
        String result = url;

        if (userSession.getUser().getUsername() != null) {
            result = result.replace("${username}", userSession.getUser().getUsername());
        }
        if (userSession.getUser().getEmail() != null) {
            result = result.replace("${email}", userSession.getUser().getEmail());
        }
        if (userSession.getUser().getId() != null) {
            result = result.replace("${userId}", userSession.getUser().getId());
        }

        return result;
    }
}
