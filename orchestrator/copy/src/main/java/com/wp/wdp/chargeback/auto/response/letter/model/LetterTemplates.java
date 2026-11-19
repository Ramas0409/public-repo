package com.wp.wdp.chargeback.auto.response.letter.model;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import lombok.Getter;
import org.springframework.core.io.ClassPathResource;
import com.wp.wdp.chargeback.auto.response.exception.ServiceStateException;

/**
 * The network letter's wording (the template resource, 2.1.0): reason-code-neutral, with one block of words per
 * network, and a layer of overrides per response type other than representment.
 */
public final class LetterTemplates {
    public static final String RESOURCE = "scenario/letter-template.json";

    @Getter
    private final String version;
    @Getter
    private final Map<String, LetterNetworkWords> networks;
    @Getter
    private final String headlineGround;
    @Getter
    private final String headlinePrecondition;
    @Getter
    private final String parties;
    @Getter
    private final String defaultIssuerName;
    @Getter
    private final String tileNetworkCase;
    @Getter
    private final String tileArn;
    @Getter
    private final String tileCard;
    @Getter
    private final String tileTransaction;
    @Getter
    private final String transactionValue;
    @Getter
    private final String tileDisputed;
    @Getter
    private final String tileAccepted;
    @Getter
    private final String tileContested;
    @Getter
    private final String amountNoteExceeds;
    @Getter
    private final String amountNoteCurrency;
    @Getter
    private final String responseHeading;
    @Getter
    private final String responseFull;
    @Getter
    private final String responsePartial;
    @Getter
    private final String claimHeading;
    @Getter
    private final String claim;
    @Getter
    private final String argumentsHeading;
    @Getter
    private final String argumentHeading;
    @Getter
    private final String bankruptcyRecorded;
    @Getter
    private final String bankruptcyNotRecorded;
    @Getter
    private final String requestHeading;
    @Getter
    private final String requestFull;
    @Getter
    private final String requestPartial;
    @Getter
    private final String requestReference;
    @Getter
    private final String requestReferenceArn;
    @Getter
    private final String attachmentsHeading;
    @Getter
    private final String attachmentEntry;
    @Getter
    private final String attachmentIssuer;
    @Getter
    private final String attachmentMerchant;
    @Getter
    private final String attachmentCitation;
    @Getter
    private final String attachmentCitationPages;
    @Getter
    private final String attachmentPageLabel;
    @Getter
    private final String ruleReferenceEntry;
    @Getter
    private final String footer;
    @Getter
    private final String footerNoNetworkCase;
    @Getter
    private final String pdfTitle;
    private final Map<String, LetterTemplates> responseTypes;

    public LetterTemplates(String version, Map<String, LetterNetworkWords> networks, Map<String, String> letter) {
        this(version, networks, letter, Map.of());
    }

    public LetterTemplates(String version, Map<String, LetterNetworkWords> networks, Map<String, String> letter,
                           Map<String, LetterTemplates> responseTypes) {
        this.version = version;
        this.responseTypes = responseTypes;
        this.networks = networks;
        this.headlineGround = letter.get("headlineGround");
        this.headlinePrecondition = letter.get("headlinePrecondition");
        this.parties = letter.get("parties");
        this.defaultIssuerName = letter.get("defaultIssuerName");
        this.tileNetworkCase = letter.get("tileNetworkCase");
        this.tileArn = letter.get("tileArn");
        this.tileCard = letter.get("tileCard");
        this.tileTransaction = letter.get("tileTransaction");
        this.transactionValue = letter.get("transactionValue");
        this.tileDisputed = letter.get("tileDisputed");
        this.tileAccepted = letter.get("tileAccepted");
        this.tileContested = letter.get("tileContested");
        this.amountNoteExceeds = letter.get("amountNoteExceeds");
        this.amountNoteCurrency = letter.get("amountNoteCurrency");
        this.responseHeading = letter.get("responseHeading");
        this.responseFull = letter.get("responseFull");
        this.responsePartial = letter.get("responsePartial");
        this.claimHeading = letter.get("claimHeading");
        this.claim = letter.get("claim");
        this.argumentsHeading = letter.get("argumentsHeading");
        this.argumentHeading = letter.get("argumentHeading");
        this.bankruptcyRecorded = letter.get("bankruptcyRecorded");
        this.bankruptcyNotRecorded = letter.get("bankruptcyNotRecorded");
        this.requestHeading = letter.get("requestHeading");
        this.requestFull = letter.get("requestFull");
        this.requestPartial = letter.get("requestPartial");
        this.requestReference = letter.get("requestReference");
        this.requestReferenceArn = letter.get("requestReferenceArn");
        this.attachmentsHeading = letter.get("attachmentsHeading");
        this.attachmentEntry = letter.get("attachmentEntry");
        this.attachmentIssuer = letter.get("attachmentIssuer");
        this.attachmentMerchant = letter.get("attachmentMerchant");
        this.attachmentCitation = letter.get("attachmentCitation");
        this.attachmentCitationPages = letter.get("attachmentCitationPages");
        this.attachmentPageLabel = letter.get("attachmentPageLabel");
        this.ruleReferenceEntry = letter.get("ruleReferenceEntry");
        this.footer = letter.get("footer");
        this.footerNoNetworkCase = letter.get("footerNoNetworkCase");
        this.pdfTitle = letter.get("pdfTitle");
    }

    /** The network's own words; null where the template carries no block for it. */
    public LetterNetworkWords network(String network) {
        return network == null ? null : networks.get(network);
    }

    /** The wording of a response type: its overrides over the base, or the base itself where it has none. */
    public LetterTemplates forResponseType(String responseType) {
        return responseType == null ? this : responseTypes.getOrDefault(responseType, this);
    }

    public static LetterTemplates load(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return of(objectMapper.readTree(in));
        } catch (IOException exception) {
            throw new ServiceStateException("the letter template could not be read", exception);
        }
    }

    public static LetterTemplates of(JsonNode root) {
        Map<String, LetterNetworkWords> networks = new LinkedHashMap<>();
        Map<String, String> letter = new LinkedHashMap<>();
        Set<String> types = new TreeSet<>();
        root.path("networks").fields().forEachRemaining(entry -> {
            networks.put(entry.getKey(), words(entry.getValue(), null));
            entry.getValue().fields().forEachRemaining(field -> addIfBlock(types, field));
        });
        root.path("letter").fields().forEachRemaining(entry -> {
            if (entry.getValue().isObject()) {
                types.add(entry.getKey());
            } else {
                letter.put(entry.getKey(), entry.getValue().asText());
            }
        });
        String version = root.path("version").asText(null);
        Map<String, LetterTemplates> responseTypes = new LinkedHashMap<>();
        for (String type : types) {
            Map<String, LetterNetworkWords> typeNetworks = new LinkedHashMap<>();
            root.path("networks").fields().forEachRemaining(entry -> typeNetworks.put(entry.getKey(),
                    words(entry.getValue(), entry.getValue().path(type))));
            Map<String, String> typeLetter = new LinkedHashMap<>(letter);
            root.path("letter").path(type).fields().forEachRemaining(entry ->
                    typeLetter.put(entry.getKey(), entry.getValue().asText()));
            responseTypes.put(type, new LetterTemplates(version, Map.copyOf(typeNetworks), typeLetter));
        }
        return new LetterTemplates(version, Map.copyOf(networks), letter, Map.copyOf(responseTypes));
    }

    private static LetterNetworkWords words(JsonNode network, JsonNode overrides) {
        JsonNode over = overrides == null ? MissingNode.getInstance() : overrides;
        return new LetterNetworkWords(over.path("kicker").asText(network.path("kicker").asText(null)),
                over.path("ruleReferencesHeading").asText(network.path("ruleReferencesHeading").asText(null)));
    }

    private static void addIfBlock(Set<String> types, Map.Entry<String, JsonNode> field) {
        if (field.getValue().isObject()) {
            types.add(field.getKey());
        }
    }
}
